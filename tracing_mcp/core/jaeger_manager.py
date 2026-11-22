import json
import os
import re
from typing import Any, Dict, List, Optional

import requests

SERVICE_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")
TRACE_ID_PATTERN = re.compile(r"^[a-fA-F0-9]{16,32}$")
DEFAULT_TIMEOUT_SECONDS = float(os.getenv("JAEGER_TIMEOUT_SECONDS", "5"))


def _extract_attr_value(attr_val: Dict[str, Any]) -> Any:
    if not isinstance(attr_val, dict):
        return attr_val
    for key in ("stringValue", "boolValue", "intValue", "doubleValue"):
        if key in attr_val:
            return attr_val[key]
    return str(attr_val)


def _extract_attributes(attributes: Optional[List[Dict[str, Any]]]) -> Dict[str, Any]:
    result: Dict[str, Any] = {}
    for attr in attributes or []:
        key = attr.get("key")
        if key:
            result[key] = _extract_attr_value(attr.get("value", {}))
    return result


def parse_otlp_payload(raw_text: str) -> List[Dict[str, Any]]:
    """
    Parse Jaeger v3 OTLP JSON response (single JSON document or newline-delimited chunks)
    into normalized trace objects grouped by traceID.
    """
    if not raw_text or not raw_text.strip():
        return []

    documents: List[Dict[str, Any]] = []
    stripped = raw_text.strip()
    try:
        parsed = json.loads(stripped)
        if isinstance(parsed, list):
            documents.extend(d for d in parsed if isinstance(d, dict))
        elif isinstance(parsed, dict):
            documents.append(parsed)
    except json.JSONDecodeError:
        for line in stripped.splitlines():
            line = line.strip()
            if not line:
                continue
            chunk = json.loads(line)
            if isinstance(chunk, dict):
                documents.append(chunk)

    traces_by_id: Dict[str, List[Dict[str, Any]]] = {}

    for doc in documents:
        container = doc.get("result", doc)
        if not isinstance(container, dict):
            continue
        resource_spans = container.get("resourceSpans") or container.get("resource_spans") or []
        for rs in resource_spans:
            resource_attrs = _extract_attributes(rs.get("resource", {}).get("attributes"))
            service_name = str(resource_attrs.get("service.name", "unknown"))
            scope_spans = rs.get("scopeSpans") or rs.get("scope_spans") or []
            for ss in scope_spans:
                for span in ss.get("spans", []):
                    trace_id = str(span.get("traceId") or span.get("trace_id") or "")
                    if not trace_id:
                        continue
                    span_id = str(span.get("spanId") or span.get("span_id") or "")
                    parent_span_id = str(span.get("parentSpanId") or span.get("parent_span_id") or "")
                    start_ns = int(span.get("startTimeUnixNano") or span.get("start_time_unix_nano") or 0)
                    end_ns = int(span.get("endTimeUnixNano") or span.get("end_time_unix_nano") or start_ns)
                    duration_us = max(0, (end_ns - start_ns) // 1000)
                    span_attrs = _extract_attributes(span.get("attributes"))
                    status = span.get("status") or {}
                    status_code = status.get("code")
                    is_error = (
                        status_code in (2, "STATUS_CODE_ERROR", "ERROR")
                        or str(span_attrs.get("error", "")).lower() == "true"
                    )
                    tags = [{"key": k, "value": v} for k, v in span_attrs.items()]
                    if is_error and not any(t["key"] == "error" for t in tags):
                        tags.append({"key": "error", "value": True})

                    traces_by_id.setdefault(trace_id, []).append({
                        "traceID": trace_id,
                        "spanID": span_id,
                        "parentSpanID": parent_span_id or None,
                        "serviceName": service_name,
                        "operationName": span.get("name", "unknown"),
                        "startTimeUnixNano": start_ns,
                        "endTimeUnixNano": end_ns,
                        "duration": duration_us,
                        "error": is_error,
                        "tags": tags,
                    })

    return [
        {"traceID": tid, "spans": spans}
        for tid, spans in traces_by_id.items()
    ]


class JaegerManager:
    """
    Jaeger v3 HTTP API client (/api/v3/services and /api/v3/traces) with OTLP JSON parsing.
    """

    def __init__(self, endpoint: Optional[str] = None, timeout: float = DEFAULT_TIMEOUT_SECONDS):
        base = (endpoint or os.getenv("JAEGER_QUERY_URL", "http://jaeger:16686")).rstrip("/")
        if base.endswith("/api/v3"):
            self.base_v3 = base
        else:
            self.base_v3 = f"{base}/api/v3"
        self.timeout = timeout

    def list_services(self) -> List[str]:
        url = f"{self.base_v3}/services"
        res = requests.get(url, timeout=self.timeout)
        res.raise_for_status()
        payload = res.json()
        return list(payload.get("services") or payload.get("data") or [])

    def get_traces(self, service: str, limit: int = 20) -> List[Dict[str, Any]]:
        if not service or not SERVICE_NAME_PATTERN.match(service):
            raise ValueError("Invalid service identifier")
        bounded_limit = max(1, min(int(limit), 100))
        url = f"{self.base_v3}/traces"
        res = requests.get(
            url,
            params={
                "query.service_name": service,
                "query.num_traces": bounded_limit,
            },
            timeout=self.timeout,
        )
        res.raise_for_status()
        return parse_otlp_payload(res.text)

    def get_trace(self, trace_id: str) -> Optional[Dict[str, Any]]:
        if not trace_id or not TRACE_ID_PATTERN.match(trace_id):
            raise ValueError("Invalid trace_id format; expected 16-32 hex characters")
        url = f"{self.base_v3}/traces/{trace_id}"
        res = requests.get(url, timeout=self.timeout)
        if res.status_code == 404:
            return None
        res.raise_for_status()
        traces = parse_otlp_payload(res.text)
        return traces[0] if traces else None
