#!/usr/bin/env python3
"""
Repeatable live acceptance & contract smoke test suite for HealOps Incident Autopilot
running under `SPRING_PROFILES_ACTIVE=demo docker compose --profile demo up -d --build`.

Verifies all Phase 11 acceptance criteria without weakening security, RBAC, CSRF, or verification invariants:
1. Live service healthchecks & MCP adapter contract checks
2. Happy-path end-to-end remediation (fault injection, deduplication, evidence, cited diagnosis,
   allowlisted action, stale/duplicate 409 protection, container restart execution, AFTER recovery,
   CLOSED status, Markdown/JSON report, draft runbook)
3. Failure & rollback path (reversible action verification failure -> ROLLING_BACK -> ROLLED_BACK
   with POST_ROLLBACK snapshot and terminal report)
4. Security acceptance (viewer 403 on write/approve/publish, cross-environment 403, SSE query-token rejection)
5. Durability acceptance (agentservice container restart -> PostgreSQL persistence, conversation history,
   and SSE Last-Event-ID replay)
6. Observability acceptance (Jaeger v3 traces, Loki logs, Prometheus scraping OTel Collector metrics)
"""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
import subprocess
import sys
import time
import traceback
from typing import Any, Callable, Dict, List, Optional, Tuple
import uuid

import requests
from urllib3.exceptions import ReadTimeoutError as Urllib3ReadTimeoutError


DEFAULT_UI_BASE = os.getenv("HEALOPS_UI_URL", "http://localhost")
DEFAULT_API_DIRECT = os.getenv("HEALOPS_API_URL", "http://localhost:8888")
DEFAULT_DEMO_WORKLOAD = os.getenv("HEALOPS_DEMO_WORKLOAD_URL", "http://localhost:8095")
DEFAULT_DOCKER_MCP = os.getenv("HEALOPS_DOCKER_MCP_URL", "http://localhost:8000")
DEFAULT_MONITORING_MCP = os.getenv("HEALOPS_MONITORING_MCP_URL", "http://localhost:9001")
DEFAULT_OPENSTACK_MCP = os.getenv("HEALOPS_OPENSTACK_MCP_URL", "http://localhost:7779")
DEFAULT_TRACING_MCP = os.getenv("HEALOPS_TRACING_MCP_URL", "http://localhost:9005")
DEFAULT_JAEGER = os.getenv("HEALOPS_JAEGER_URL", "http://localhost:16686")
DEFAULT_LOKI = os.getenv("HEALOPS_LOKI_URL", "http://localhost:3100")
DEFAULT_PROMETHEUS = os.getenv("HEALOPS_PROMETHEUS_URL", "http://localhost:9090")
DEFAULT_OTEL_HEALTH = os.getenv("HEALOPS_OTEL_HEALTH_URL", "http://localhost:13133")
DEFAULT_OTEL_METRICS = os.getenv("HEALOPS_OTEL_METRICS_URL", "http://localhost:8889/metrics")

OPERATOR_USER = os.getenv("HEALOPS_OPERATOR_USER", "operator")
OPERATOR_PASS = os.getenv("HEALOPS_OPERATOR_PASSWORD", "operator-local-dev")
VIEWER_USER = os.getenv("HEALOPS_VIEWER_USER", "viewer")
VIEWER_PASS = os.getenv("HEALOPS_VIEWER_PASSWORD", "viewer-local-dev")


class AcceptanceError(AssertionError):
    pass


def log_step(section: str, message: str) -> None:
    print(f"[{section}] {message}", flush=True)


def assert_true(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceError(message)


def poll_until(
    description: str,
    fn: Callable[[], Any],
    timeout_s: float = 60.0,
    interval_s: float = 1.5,
) -> Any:
    deadline = time.monotonic() + timeout_s
    last_error: Optional[Exception] = None
    while time.monotonic() < deadline:
        try:
            result = fn()
            if result:
                return result
        except Exception as exc:
            last_error = exc
        time.sleep(interval_s)
    detail = f" (last error: {last_error})" if last_error else ""
    raise AcceptanceError(f"Timed out after {timeout_s}s waiting for: {description}{detail}")


class HealOpsClient:
    """HTTP client talking through the same-origin Nginx gateway with Basic Auth + CSRF cookie."""

    def __init__(self, base_url: str, username: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.auth = (username, password)
        self.username = username

    def prime_csrf(self) -> str:
        resp = self.session.get(f"{self.base_url}/api/v1/auth/me", timeout=10)
        assert_true(
            resp.status_code == 200,
            f"Expected 200 from /api/v1/auth/me for {self.username}, got {resp.status_code}: {resp.text}",
        )
        token = self.session.cookies.get("XSRF-TOKEN", "")
        if not token:
            # Trigger CSRF cookie issuance via another authenticated GET if needed
            resp2 = self.session.get(f"{self.base_url}/api/v1/environments", timeout=10)
            assert_true(resp2.status_code == 200, f"GET /api/v1/environments failed: {resp2.status_code}")
            token = self.session.cookies.get("XSRF-TOKEN", "")
        return token

    def _csrf_headers(self, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
        token = self.session.cookies.get("XSRF-TOKEN") or self.prime_csrf()
        headers = {"X-XSRF-TOKEN": token} if token else {}
        if extra:
            headers.update(extra)
        return headers

    def get(self, path: str, params: Optional[Dict[str, Any]] = None, headers: Optional[Dict[str, str]] = None, timeout: float = 15) -> requests.Response:
        return self.session.get(f"{self.base_url}{path}", params=params, headers=headers, timeout=timeout)

    def post(
        self,
        path: str,
        json_body: Any = None,
        params: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
        timeout: float = 20,
    ) -> requests.Response:
        return self.session.post(
            f"{self.base_url}{path}",
            json=json_body,
            params=params,
            headers=self._csrf_headers(headers),
            timeout=timeout,
        )

    def read_sse_events(
        self,
        environment_id: str,
        last_event_id: Optional[str] = None,
        max_events: int = 20,
        read_seconds: float = 3.0,
    ) -> List[Dict[str, Any]]:
        headers = {"Accept": "text/event-stream"}
        if last_event_id is not None:
            headers["Last-Event-ID"] = str(last_event_id)
        events: List[Dict[str, Any]] = []
        resp = self.session.get(
            f"{self.base_url}/api/v1/events",
            params={"environmentId": environment_id},
            headers=headers,
            stream=True,
            timeout=(10, read_seconds),
        )
        if resp.status_code != 200:
            raise AcceptanceError(f"SSE stream failed with status {resp.status_code}: {resp.text}")
        current: Dict[str, Any] = {}
        try:
            for raw_line in resp.iter_lines(chunk_size=1, decode_unicode=True):
                if raw_line is None:
                    continue
                line = raw_line.strip()
                if not line:
                    if current:
                        events.append(current)
                        current = {}
                        if len(events) >= max_events:
                            break
                    continue
                if line.startswith(":"):
                    continue
                if line.startswith("id:"):
                    current["id"] = line[3:].strip()
                elif line.startswith("event:"):
                    current["event"] = line[6:].strip()
                elif line.startswith("data:"):
                    data_str = line[5:].strip()
                    try:
                        current["data"] = json.loads(data_str)
                    except json.JSONDecodeError:
                        current["data"] = data_str
        except Exception as exc:
            if "timed out" not in str(exc).lower() and not isinstance(
                exc, (requests.exceptions.RequestException, Urllib3ReadTimeoutError, TimeoutError)
            ):
                raise
        finally:
            resp.close()
        if current:
            events.append(current)
        return events


def check_live_contracts(operator: HealOpsClient, with_openstack: bool = False) -> None:
    log_step("CONTRACTS", "Checking service healthchecks and live MCP adapter contracts...")

    # 1. Control plane & UI gateway
    health = poll_until(
        "agentservice actuator health UP",
        lambda: requests.get(f"{DEFAULT_API_DIRECT}/actuator/health", timeout=5).status_code == 200
        and requests.get(f"{DEFAULT_API_DIRECT}/actuator/health", timeout=5).json().get("status") == "UP",
        timeout_s=90,
    )
    assert_true(bool(health), "agentservice health check did not report UP")

    ui_root = requests.get(f"{DEFAULT_UI_BASE}/", timeout=10)
    assert_true(ui_root.status_code == 200 and "<app-root" in ui_root.text, f"UI gateway failed: {ui_root.status_code}")

    envs_resp = operator.get("/api/v1/environments")
    assert_true(envs_resp.status_code == 200, f"GET /api/v1/environments failed: {envs_resp.status_code}")
    env_ids = {e["id"] for e in envs_resp.json()}
    assert_true("demo" in env_ids, f"Expected seeded 'demo' environment in {env_ids} (ensure SPRING_PROFILES_ACTIVE=demo)")

    # 2. docker-mcp contract
    docker_list = requests.get(f"{DEFAULT_DOCKER_MCP}/docker/list", params={"all": "true"}, timeout=10)
    assert_true(docker_list.status_code == 200, f"docker-mcp /docker/list failed: {docker_list.status_code}")
    containers = docker_list.json()
    assert_true(isinstance(containers, list) and len(containers) > 0, "docker-mcp returned empty container list")
    container_names = {c.get("name") for c in containers}
    assert_true("healops-demo-workload" in container_names, f"healops-demo-workload not visible in docker-mcp: {container_names}")

    # 3. monitoring-mcp contract
    mon_root = requests.get(f"{DEFAULT_MONITORING_MCP}/", timeout=10)
    assert_true(mon_root.status_code == 200, f"monitoring-mcp root failed: {mon_root.status_code}")
    mon_metrics = requests.get(f"{DEFAULT_MONITORING_MCP}/metrics/container/healops-demo-workload", timeout=10)
    assert_true(mon_metrics.status_code in (200, 404), f"monitoring-mcp metrics contract unexpected status: {mon_metrics.status_code}")

    # 4. openstack-mcp contract (optional --profile openstack)
    if with_openstack:
        os_root = requests.get(f"{DEFAULT_OPENSTACK_MCP}/", timeout=10)
        assert_true(os_root.status_code == 200, f"openstack-mcp root failed: {os_root.status_code}")
        os_events = requests.get(f"{DEFAULT_OPENSTACK_MCP}/api/v1/openstack/events", timeout=10)
        assert_true(os_events.status_code == 200, f"openstack-mcp /events failed: {os_events.status_code}")

    # 5. tracing-mcp contract (Jaeger v3)
    tr_root = requests.get(f"{DEFAULT_TRACING_MCP}/", timeout=10)
    assert_true(tr_root.status_code == 200, f"tracing-mcp root failed: {tr_root.status_code}")
    tr_services = requests.get(f"{DEFAULT_TRACING_MCP}/apm/services", timeout=10)
    assert_true(tr_services.status_code == 200, f"tracing-mcp /apm/services failed: {tr_services.status_code}")

    # 6. demo-workload contract
    dw_health = requests.get(f"{DEFAULT_DEMO_WORKLOAD}/health", timeout=10)
    assert_true(dw_health.status_code == 200, f"demo-workload /health failed: {dw_health.status_code}")
    dw_metrics = requests.get(f"{DEFAULT_DEMO_WORKLOAD}/metrics", timeout=10)
    assert_true(dw_metrics.status_code == 200 and "demo_workload_up" in dw_metrics.text, "demo-workload /metrics missing expected metrics")

    # 7. Observability core readiness
    otel_h = requests.get(DEFAULT_OTEL_HEALTH, timeout=10)
    assert_true(otel_h.status_code == 200, f"otel-collector health failed: {otel_h.status_code}")
    jaeger_svc = requests.get(f"{DEFAULT_JAEGER}/api/v3/services", timeout=10)
    assert_true(jaeger_svc.status_code == 200, f"Jaeger v3 /api/v3/services failed: {jaeger_svc.status_code}")
    loki_ready = poll_until(
        "Loki /ready",
        lambda: requests.get(f"{DEFAULT_LOKI}/ready", timeout=5).status_code == 200,
        timeout_s=45,
    )
    assert_true(bool(loki_ready), "Loki did not become ready")
    prom_ready = requests.get(f"{DEFAULT_PROMETHEUS}/-/ready", timeout=10)
    assert_true(prom_ready.status_code == 200, f"Prometheus /-/ready failed: {prom_ready.status_code}")

    log_step("CONTRACTS", "PASS: All services healthy and adapter contracts verified.")


def run_happy_path(operator: HealOpsClient) -> Tuple[str, str, str, str]:
    """
    Executes happy-path acceptance against live demo-workload & docker-mcp.
    Returns (incident_id, action_id, draft_runbook_id, trace_id).
    """
    log_step("HAPPY_PATH", "Resetting demo-workload and injecting latency + 100% error + unhealthy faults...")
    assert_true(requests.post(f"{DEFAULT_DEMO_WORKLOAD}/faults/reset", timeout=5).status_code == 200, "Reset failed")
    assert_true(requests.post(f"{DEFAULT_DEMO_WORKLOAD}/faults/latency", params={"ms": 1200}, timeout=5).status_code == 200, "Latency fault failed")
    assert_true(requests.post(f"{DEFAULT_DEMO_WORKLOAD}/faults/error-rate", params={"rate": 1.0}, timeout=5).status_code == 200, "Error-rate fault failed")
    assert_true(requests.post(f"{DEFAULT_DEMO_WORKLOAD}/faults/unhealthy", params={"enabled": "true"}, timeout=5).status_code == 200, "Unhealthy fault failed")

    # Emit synthetic failing workload traffic with OTLP trace, log, and metric
    work_resp = requests.get(f"{DEFAULT_DEMO_WORKLOAD}/work", timeout=10)
    assert_true(work_resp.status_code == 500, f"Expected 500 from faulted demo-workload /work, got {work_resp.status_code}")
    trace_id = work_resp.json()["detail"]["traceId"]
    assert_true(len(trace_id) == 32, f"Invalid traceId returned by demo-workload: {trace_id}")
    # Second request for additional span/log/metric volume
    requests.get(f"{DEFAULT_DEMO_WORKLOAD}/work", timeout=10)
    log_step("HAPPY_PATH", f"Injected faults and captured live OTLP traceId={trace_id}")

    fingerprint = f"accept-happy-{uuid.uuid4().hex[:12]}"
    alert_payload = {
        "source": "alertmanager",
        "externalAlertId": f"ext-{fingerprint}",
        "fingerprint": fingerprint,
        "environmentId": "demo",
        "severity": "HIGH",
        "service": "demo-workload",
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "labels": {
            "runtime": "docker",
            "container": "healops-demo-workload",
        },
        "annotations": {
            "summary": "demo-workload elevated latency (1200ms), 100% error rate, and unhealthy healthcheck",
            "error_rate": "100%",
            "latency_ms": "1200ms",
            "status": "unhealthy",
        },
        "traceId": trace_id,
    }

    create_resp = operator.post("/api/v1/incidents", json_body=alert_payload)
    assert_true(create_resp.status_code == 201, f"Expected 201 on alert intake, got {create_resp.status_code}: {create_resp.text}")
    incident = create_resp.json()
    incident_id = incident["id"]

    # Duplicate alert intake must deduplicate (200 OK, same incident ID)
    dup_resp = operator.post("/api/v1/incidents", json_body=alert_payload)
    assert_true(dup_resp.status_code == 200, f"Expected 200 on duplicate alert, got {dup_resp.status_code}: {dup_resp.text}")
    assert_true(dup_resp.json()["id"] == incident_id, "Duplicate alert created a new incident instead of deduplicating!")
    log_step("HAPPY_PATH", f"Deduplicated incident created: id={incident_id}")

    # Wait for automated orchestration to reach AWAITING_APPROVAL
    def _poll_happy_awaiting() -> Optional[Dict[str, Any]]:
        data = operator.get(f"/api/v1/incidents/{incident_id}").json()
        return data if data.get("status") == "AWAITING_APPROVAL" else None

    incident = poll_until(
        f"incident {incident_id} to reach AWAITING_APPROVAL",
        _poll_happy_awaiting,
        timeout_s=60,
    )

    # Confirm evidence collection
    evidence = operator.get(f"/api/v1/incidents/{incident_id}/evidence").json()
    evidence_ids = {item["id"] for item in evidence}
    sources = {item["source"] for item in evidence}
    assert_true(len(evidence) >= 2 and "docker" in sources, f"Expected alert + docker evidence, got sources={sources}")

    # Confirm diagnosis cites stored evidence IDs
    diagnoses = operator.get(f"/api/v1/incidents/{incident_id}/diagnoses").json()
    assert_true(len(diagnoses) >= 1, "No diagnosis persisted")
    hypotheses = diagnoses[0].get("hypotheses") or []
    cited_ids = {eid for h in hypotheses for eid in (h.get("evidenceIds") or [])}
    assert_true(len(cited_ids) > 0 and cited_ids.issubset(evidence_ids), f"Diagnosis citations {cited_ids} not subset of stored evidence {evidence_ids}")

    # Confirm allowlisted proposed action
    actions = operator.get(f"/api/v1/incidents/{incident_id}/proposed-actions").json()
    assert_true(len(actions) >= 1, "No proposed action created")
    action = actions[0]
    action_id = action["id"]
    assert_true(action["actionType"] == "RESTART_CONTAINER", f"Expected RESTART_CONTAINER, got {action['actionType']}")
    assert_true(action["arguments"].get("container") == "healops-demo-workload", f"Unexpected container arg: {action['arguments']}")
    assert_true(action["status"] == "PROPOSED", f"Unexpected action status: {action['status']}")
    log_step("HAPPY_PATH", f"Verified evidence ({len(evidence)}), cited diagnosis, and allowlisted action {action_id} ({action['actionType']})")

    # Stale expectedVersion -> 409 Conflict
    stale_resp = operator.post(
        f"/api/v1/approvals/{action_id}/decisions",
        json_body={
            "decision": "APPROVED",
            "reason": "Stale version check",
            "expectedVersion": action["version"] + 99,
        },
        headers={"Idempotency-Key": f"stale-{action_id}"},
    )
    assert_true(stale_resp.status_code == 409, f"Expected 409 on stale version, got {stale_resp.status_code}: {stale_resp.text}")

    # Approve once with Idempotency-Key -> 201 Created
    idem_key = f"approve-{action_id}"
    expected_reason = "Approved during Phase 11 live acceptance"
    approve_resp = operator.post(
        f"/api/v1/approvals/{action_id}/decisions",
        json_body={
            "decision": "APPROVED",
            "reason": expected_reason,
            "expectedVersion": action["version"],
        },
        headers={"Idempotency-Key": idem_key},
    )
    assert_true(approve_resp.status_code == 201, f"Expected 201 on approval, got {approve_resp.status_code}: {approve_resp.text}")
    assert_true(approve_resp.json()["actor"] == OPERATOR_USER, f"Unexpected actor: {approve_resp.json()}")
    assert_true(approve_resp.json().get("reason") == expected_reason, f"Approval reason not persisted: {approve_resp.json()}")

    # Duplicate approval with SAME Idempotency-Key -> 200 OK (idempotent replay, no second execution)
    dup_approve = operator.post(
        f"/api/v1/approvals/{action_id}/decisions",
        json_body={
            "decision": "APPROVED",
            "reason": "Duplicate click with same idempotency key",
            "expectedVersion": action["version"],
        },
        headers={"Idempotency-Key": idem_key},
    )
    assert_true(dup_approve.status_code == 200, f"Expected 200 on idempotent replay, got {dup_approve.status_code}: {dup_approve.text}")

    # Second decision with NEW Idempotency-Key -> 409 Conflict
    second_approve = operator.post(
        f"/api/v1/approvals/{action_id}/decisions",
        json_body={
            "decision": "APPROVED",
            "reason": "Second distinct decision must fail with 409",
            "expectedVersion": action["version"],
        },
        headers={"Idempotency-Key": f"approve-second-{action_id}"},
    )
    assert_true(second_approve.status_code == 409, f"Expected 409 on second approval decision, got {second_approve.status_code}: {second_approve.text}")
    log_step("HAPPY_PATH", "Verified approval once + stale/duplicate 409 protection; waiting for live restart & AFTER verification...")

    # Wait for execution + stabilization + AFTER verification -> CLOSED
    def _poll_happy_terminal() -> Optional[Dict[str, Any]]:
        data = operator.get(f"/api/v1/incidents/{incident_id}").json()
        return data if r_is_terminal(data) else None

    closed = poll_until(
        f"incident {incident_id} to reach CLOSED",
        _poll_happy_terminal,
        timeout_s=90,
        interval_s=2.0,
    )
    assert_true(closed["status"] == "CLOSED", f"Expected happy-path incident to reach CLOSED, got {closed['status']}")

    # Confirm exactly one execution succeeded and demo-workload is healthy again after live Docker restart
    executions = operator.get("/api/v1/tool-executions", params={"incidentId": incident_id}).json()
    assert_true(len(executions) == 1 and executions[0]["status"] == "SUCCEEDED", f"Expected 1 SUCCEEDED execution, got {executions}")

    dw_recovered = poll_until(
        "demo-workload /health 200 after restart",
        lambda: requests.get(f"{DEFAULT_DEMO_WORKLOAD}/health", timeout=5).status_code == 200,
        timeout_s=30,
    )
    assert_true(bool(dw_recovered), "demo-workload did not recover to 200 healthy after container restart")

    # Confirm BEFORE and AFTER health snapshots
    health_cmp = operator.get(f"/api/v1/incidents/{incident_id}/health-comparisons").json()
    assert_true(health_cmp.get("before") is not None and health_cmp.get("after") is not None, f"Missing BEFORE/AFTER snapshots: {health_cmp}")
    assert_true(health_cmp["after"]["signals"].get("healthStatus") == "HEALTHY", f"AFTER snapshot not HEALTHY: {health_cmp['after']}")
    assert_true(health_cmp["after"]["signals"].get("telemetryStatus") == "COMPLETE", f"AFTER telemetry not COMPLETE: {health_cmp['after']}")

    # Confirm generated Markdown + JSON incident report
    def _poll_happy_report() -> Optional[Dict[str, Any]]:
        resp = operator.get(f"/api/v1/incidents/{incident_id}/report")
        return resp.json() if resp.status_code == 200 else None

    report = poll_until(
        f"final incident report for {incident_id}",
        _poll_happy_report,
        timeout_s=30,
    )
    assert_true(bool(report.get("renderedMarkdown")) and incident_id in report["renderedMarkdown"], "Missing or incomplete Markdown report")
    assert_true(
        report.get("finalOutcome") == "CLOSED" and isinstance(report.get("structuredReport"), dict) and len(report["structuredReport"]) > 0,
        f"Unexpected JSON report: {report}",
    )

    # Draft a runbook from the closed incident
    draft_resp = operator.post(
        f"/api/v1/runbooks/draft-from-incident/{incident_id}",
        json_body={"name": f"Acceptance Recovery Runbook {uuid.uuid4().hex[:6]}"},
    )
    assert_true(draft_resp.status_code == 201, f"Expected 201 drafting runbook, got {draft_resp.status_code}: {draft_resp.text}")
    draft_runbook = draft_resp.json()
    assert_true(draft_runbook["status"] == "DRAFT", f"Expected DRAFT runbook status, got {draft_runbook['status']}")
    log_step("HAPPY_PATH", f"PASS: Incident CLOSED, AFTER=HEALTHY, report generated, draft runbook id={draft_runbook['id']}")

    return incident_id, action_id, draft_runbook["id"], trace_id


def r_is_terminal(incident_json: Dict[str, Any]) -> bool:
    return incident_json.get("status") in {"CLOSED", "ROLLED_BACK", "FAILED", "CANCELLED"}


def run_rollback_path(operator: HealOpsClient) -> str:
    """
    Verifies failed verification on a reversible action (SCALE_K8S_DEPLOYMENT in demo-sandbox)
    transitions ROLLING_BACK -> ROLLED_BACK with POST_ROLLBACK snapshot and terminal report.
    """
    log_step("ROLLBACK_PATH", "Triggering reversible SCALE_K8S_DEPLOYMENT scenario in demo-sandbox...")
    fp = f"accept-rollback-{uuid.uuid4().hex[:12]}"
    payload = {
        "source": "alertmanager",
        "externalAlertId": f"ext-{fp}",
        "fingerprint": fp,
        "environmentId": "demo",
        "severity": "MEDIUM",
        "service": "demo-rollback-api",
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "labels": {
            "namespace": "demo-sandbox",
            "deployment": "demo-rollback-api",
            "replicas": "2",
        },
        "annotations": {
            "summary": "Sustained elevated request latency (1850ms) on demo-rollback-api",
            "latency_ms": "1850ms",
        },
    }
    resp = operator.post("/api/v1/incidents", json_body=payload)
    assert_true(resp.status_code == 201, f"Rollback incident creation failed: {resp.status_code} {resp.text}")
    incident_id = resp.json()["id"]

    def _poll_rb_awaiting() -> Optional[Dict[str, Any]]:
        data = operator.get(f"/api/v1/incidents/{incident_id}").json()
        return data if data.get("status") == "AWAITING_APPROVAL" else None

    poll_until(
        f"rollback incident {incident_id} to reach AWAITING_APPROVAL",
        _poll_rb_awaiting,
        timeout_s=60,
    )

    actions = operator.get(f"/api/v1/incidents/{incident_id}/proposed-actions").json()
    assert_true(len(actions) >= 1, "No proposed action for rollback scenario")
    action = actions[0]
    assert_true(action["actionType"] == "SCALE_K8S_DEPLOYMENT", f"Expected SCALE_K8S_DEPLOYMENT, got {action['actionType']}")

    approve = operator.post(
        f"/api/v1/approvals/{action['id']}/decisions",
        json_body={
            "decision": "APPROVED",
            "reason": "Approve reversible scale action to verify automatic rollback on failed verification",
            "expectedVersion": action["version"],
        },
        headers={"Idempotency-Key": f"approve-rb-{action['id']}"},
    )
    assert_true(approve.status_code == 201, f"Approval failed: {approve.status_code} {approve.text}")

    def _poll_rb_terminal() -> Optional[Dict[str, Any]]:
        data = operator.get(f"/api/v1/incidents/{incident_id}").json()
        return data if r_is_terminal(data) else None

    terminal = poll_until(
        f"rollback incident {incident_id} to reach terminal status",
        _poll_rb_terminal,
        timeout_s=90,
        interval_s=2.0,
    )
    assert_true(terminal["status"] == "ROLLED_BACK", f"Expected ROLLED_BACK, got {terminal['status']}")

    timeline = operator.get(f"/api/v1/incidents/{incident_id}/timeline").json()
    to_statuses = [ev.get("toStatus") for ev in timeline]
    assert_true("ROLLING_BACK" in to_statuses and "ROLLED_BACK" in to_statuses, f"Timeline missing rollback states: {to_statuses}")

    health_cmp = operator.get(f"/api/v1/incidents/{incident_id}/health-comparisons").json()
    assert_true(health_cmp.get("before") is not None, "Missing BEFORE snapshot on rollback incident")
    assert_true(health_cmp.get("after") is not None, "Missing AFTER snapshot on rollback incident")
    assert_true(health_cmp.get("postRollback") is not None, "Missing POST_ROLLBACK snapshot on rollback incident")

    executions = operator.get("/api/v1/tool-executions", params={"incidentId": incident_id}).json()
    keys = [e.get("idempotencyKey", "") for e in executions]
    assert_true(any(k.startswith("exec:") for k in keys) and any(k.startswith("rollback:") for k in keys), f"Expected forward + rollback executions, got {keys}")

    def _poll_rb_report() -> Optional[Dict[str, Any]]:
        resp = operator.get(f"/api/v1/incidents/{incident_id}/report")
        return resp.json() if resp.status_code == 200 else None

    report = poll_until(
        f"terminal report for rolled-back incident {incident_id}",
        _poll_rb_report,
        timeout_s=30,
    )
    assert_true("ROLLED_BACK" in report.get("renderedMarkdown", ""), "Rollback terminal report missing ROLLED_BACK status")
    log_step("ROLLBACK_PATH", f"PASS: Incident {incident_id} transitioned ROLLING_BACK -> ROLLED_BACK with POST_ROLLBACK snapshot and report.")
    return incident_id


def run_security_checks(operator: HealOpsClient, viewer: HealOpsClient, action_id: str, runbook_id: str) -> None:
    log_step("SECURITY", "Verifying RBAC (viewer 403), cross-environment isolation (403), and SSE query-token rejection (400)...")
    viewer.prime_csrf()

    # Viewer cannot create incidents (403)
    v_write = viewer.post(
        "/api/v1/incidents",
        json_body={
            "source": "manual",
            "externalAlertId": "viewer-forbidden",
            "fingerprint": f"viewer-fp-{uuid.uuid4().hex[:8]}",
            "environmentId": "demo",
            "severity": "LOW",
            "service": "demo-workload",
            "startedAt": datetime.now(timezone.utc).isoformat(),
            "labels": {},
            "annotations": {},
        },
    )
    assert_true(v_write.status_code == 403, f"Expected 403 for viewer incident write, got {v_write.status_code}")

    # Viewer cannot approve actions (403)
    v_approve = viewer.post(
        f"/api/v1/approvals/{action_id}/decisions",
        json_body={"decision": "APPROVED", "reason": "Viewer must not approve", "expectedVersion": 0},
        headers={"Idempotency-Key": f"viewer-deny-{uuid.uuid4().hex[:8]}"},
    )
    assert_true(v_approve.status_code == 403, f"Expected 403 for viewer approval, got {v_approve.status_code}")

    # Viewer cannot publish runbooks (403)
    v_publish = viewer.post(f"/api/v1/runbooks/{runbook_id}/publish")
    assert_true(v_publish.status_code == 403, f"Expected 403 for viewer runbook publish, got {v_publish.status_code}")

    # Cross-environment access denied (operator lacks env:prod)
    x_overview = operator.get("/api/v1/overview", params={"environmentId": "prod"})
    assert_true(x_overview.status_code == 403, f"Expected 403 for cross-env overview, got {x_overview.status_code}")

    x_sse = operator.get("/api/v1/events", params={"environmentId": "prod"})
    assert_true(x_sse.status_code == 403, f"Expected 403 for cross-env SSE, got {x_sse.status_code}")

    x_conv = operator.post("/api/v1/conversations", json_body={"environmentId": "prod", "title": "Unauthorized env"})
    assert_true(x_conv.status_code == 403, f"Expected 403 for cross-env conversation, got {x_conv.status_code}")

    # SSE rejects query-string tokens (400)
    tok_sse = operator.get("/api/v1/events", params={"environmentId": "demo", "token": "leaked-jwt"})
    assert_true(tok_sse.status_code == 400, f"Expected 400 for SSE query-string token, got {tok_sse.status_code}")

    log_step("SECURITY", "PASS: Viewer 403s, cross-environment 403s, and SSE query-token 400 all enforced.")


def run_durability_checks(
    operator: HealOpsClient,
    happy_incident_id: str,
    rollback_incident_id: str,
    skip_restart: bool,
    env_file: Optional[str] = None,
) -> None:
    log_step("DURABILITY", "Creating persistent conversation and capturing SSE sequence before restart...")
    conv_resp = operator.post(
        "/api/v1/conversations",
        json_body={"environmentId": "demo", "title": "Phase 11 Durability Session"},
    )
    assert_true(conv_resp.status_code == 201, f"Failed creating conversation: {conv_resp.status_code} {conv_resp.text}")
    session_id = conv_resp.json()["id"]

    msg_resp = operator.post(
        f"/api/v1/conversations/{session_id}/messages",
        json_body={
            "content": "Summarize the recovered demo-workload incident for durability verification.",
            "incidentId": happy_incident_id,
        },
    )
    assert_true(msg_resp.status_code == 202, f"Expected 202 posting message, got {msg_resp.status_code}: {msg_resp.text}")

    # Wait for async assistant reply persistence
    def _poll_assistant_reply() -> Optional[Dict[str, Any]]:
        detail = operator.get(f"/api/v1/conversations/{session_id}").json()
        return detail if any(m.get("role") == "ASSISTANT" for m in detail.get("messages", [])) else None

    conv_detail = poll_until(
        f"assistant reply persisted for conversation {session_id}",
        _poll_assistant_reply,
        timeout_s=45,
    )
    pre_restart_msg_count = len(conv_detail["messages"])
    assert_true(pre_restart_msg_count >= 2, f"Expected user + assistant messages, got {pre_restart_msg_count}")
    log_step("DURABILITY", f"Conversation {session_id} persisted {pre_restart_msg_count} messages; reading SSE stream...")

    # Capture SSE replay events before restart
    pre_events = operator.read_sse_events("demo", last_event_id="0", max_events=200, read_seconds=3.0)
    assert_true(len(pre_events) >= 2, f"Expected persisted dashboard events via SSE replay, got {len(pre_events)}")
    event_ids = [int(ev["id"]) for ev in pre_events if "id" in ev and str(ev["id"]).isdigit()]
    assert_true(event_ids == sorted(event_ids) and len(event_ids) == len(set(event_ids)), f"SSE event IDs not strictly increasing/unique: {event_ids}")
    pivot_id = event_ids[-2]

    if not skip_restart:
        compose_cmd = ["docker", "compose"]
        if env_file:
            compose_cmd.extend(["--env-file", env_file])
        compose_cmd.extend(["--profile", "demo", "restart", "agentservice"])
        log_step("DURABILITY", f"Restarting agentservice container (`{' '.join(compose_cmd)}`)...")
        subprocess.run(compose_cmd, check=True)
        poll_until(
            "agentservice healthy after restart",
            lambda: requests.get(f"{DEFAULT_API_DIRECT}/actuator/health", timeout=5).status_code == 200
            and requests.get(f"{DEFAULT_API_DIRECT}/actuator/health", timeout=5).json().get("status") == "UP",
            timeout_s=90,
            interval_s=2.0,
        )
        # Re-prime CSRF cookie after container restart
        operator.session.cookies.clear()
        operator.prime_csrf()

    # Verify PostgreSQL durability after restart
    inc_after = operator.get(f"/api/v1/incidents/{happy_incident_id}").json()
    assert_true(inc_after["status"] == "CLOSED", f"Happy incident lost/altered after restart: {inc_after}")
    rb_after = operator.get(f"/api/v1/incidents/{rollback_incident_id}").json()
    assert_true(rb_after["status"] == "ROLLED_BACK", f"Rollback incident lost/altered after restart: {rb_after}")

    conv_after = operator.get(f"/api/v1/conversations/{session_id}").json()
    assert_true(
        len(conv_after["messages"]) == pre_restart_msg_count,
        f"Conversation history mismatch after restart: before={pre_restart_msg_count}, after={len(conv_after['messages'])}",
    )

    # Verify SSE Last-Event-ID replay after restart returns strictly events > pivot_id
    replayed = operator.read_sse_events("demo", last_event_id=str(pivot_id), max_events=50, read_seconds=3.0)
    replayed_ids = [int(ev["id"]) for ev in replayed if "id" in ev and str(ev["id"]).isdigit()]
    assert_true(len(replayed_ids) >= 1, f"Expected >= 1 replayed event after Last-Event-ID={pivot_id}, got none")
    assert_true(all(eid > pivot_id for eid in replayed_ids), f"Replayed events included duplicate/older IDs <= {pivot_id}: {replayed_ids}")

    log_step(
        "DURABILITY",
        f"PASS: PostgreSQL incidents, conversation ({len(conv_after['messages'])} msgs), and SSE Last-Event-ID>{pivot_id} replay ({len(replayed_ids)} events) survived restart.",
    )


def run_observability_checks(operator: HealOpsClient, trace_id: str) -> None:
    log_step("OBSERVABILITY", "Verifying Jaeger v3 traces, Loki logs, and Prometheus OTel Collector scraping...")

    # 1. Jaeger v3 services & traces
    def _jaeger_has_trace() -> bool:
        svcs = requests.get(f"{DEFAULT_JAEGER}/api/v3/services", timeout=5)
        if svcs.status_code != 200:
            return False
        services = svcs.json().get("services", [])
        if "demo-workload" not in services:
            return False
        now = datetime.now(timezone.utc)
        tr = requests.get(
            f"{DEFAULT_JAEGER}/api/v3/traces",
            params={
                "query.service_name": "demo-workload",
                "query.start_time_min": (now - timedelta(minutes=30)).isoformat().replace("+00:00", "Z"),
                "query.start_time_max": (now + timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
                "query.search_depth": 20,
            },
            timeout=10,
        )
        return tr.status_code == 200 and trace_id in tr.text

    poll_until("Jaeger v3 /api/v3/services & /api/v3/traces to contain demo-workload trace", _jaeger_has_trace, timeout_s=30, interval_s=2.0)

    # Also verify HealOps BFF trace endpoints
    bff_traces = operator.get("/api/v1/traces", params={"environmentId": "demo", "service": "demo-workload"})
    assert_true(bff_traces.status_code == 200, f"BFF /api/v1/traces failed: {bff_traces.status_code}")

    # 2. Loki logs
    def _loki_has_logs() -> bool:
        now_ns = time.time_ns()
        start_ns = now_ns - int(30 * 60 * 1e9)
        loki_resp = requests.get(
            f"{DEFAULT_LOKI}/loki/api/v1/query_range",
            params={
                "query": '{service_name="demo-workload"}',
                "start": str(start_ns),
                "end": str(now_ns),
                "limit": 20,
            },
            timeout=10,
        )
        if loki_resp.status_code != 200:
            return False
        results = loki_resp.json().get("data", {}).get("result", [])
        return len(results) > 0 and trace_id in loki_resp.text

    poll_until("Loki to contain demo-workload logs with trace_id", _loki_has_logs, timeout_s=30, interval_s=2.0)

    # 3. Prometheus scraping OTel Collector metrics
    def _prom_scrapes_otel() -> bool:
        targets = requests.get(f"{DEFAULT_PROMETHEUS}/api/v1/targets", timeout=10)
        if targets.status_code != 200:
            return False
        active = targets.json().get("data", {}).get("activeTargets", [])
        otel_up = any(
            t.get("labels", {}).get("job") == "otel-collector" and t.get("health") == "up"
            for t in active
        )
        if not otel_up:
            return False
        q = requests.get(
            f"{DEFAULT_PROMETHEUS}/api/v1/query",
            params={"query": "healops_demo_workload_latency_ms_milliseconds"},
            timeout=10,
        )
        return q.status_code == 200 and len(q.json().get("data", {}).get("result", [])) > 0

    poll_until("Prometheus scraping OTel Collector target and healops_demo_workload_latency_ms_milliseconds", _prom_scrapes_otel, timeout_s=45, interval_s=2.5)

    log_step("OBSERVABILITY", f"PASS: Jaeger v3 trace ({trace_id}), Loki OTLP logs, and Prometheus OTel Collector metrics verified.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run HealOps Phase 11 live acceptance tests against --profile demo Compose stack.")
    parser.add_argument("--skip-restart", action="store_true", help="Skip restarting agentservice container during durability check.")
    parser.add_argument("--with-openstack", action="store_true", help="Also verify optional openstack-mcp adapter (--profile openstack).")
    parser.add_argument("--env-file", default=None, help="Optional Compose env file (e.g., .env.example) used when restarting agentservice.")
    args = parser.parse_args()

    operator = HealOpsClient(DEFAULT_UI_BASE, OPERATOR_USER, OPERATOR_PASS)
    viewer = HealOpsClient(DEFAULT_UI_BASE, VIEWER_USER, VIEWER_PASS)

    try:
        operator.prime_csrf()
        check_live_contracts(operator, with_openstack=args.with_openstack)
        happy_id, action_id, draft_runbook_id, trace_id = run_happy_path(operator)
        rollback_id = run_rollback_path(operator)
        run_security_checks(operator, viewer, action_id, draft_runbook_id)
        run_durability_checks(operator, happy_id, rollback_id, skip_restart=args.skip_restart, env_file=args.env_file)
        run_observability_checks(operator, trace_id)
    except AcceptanceError as exc:
        print(f"\n[FAIL] Acceptance check failed: {exc}", file=sys.stderr, flush=True)
        return 1
    except Exception as exc:
        print(f"\n[ERROR] Unexpected exception during acceptance run: {exc}", file=sys.stderr, flush=True)
        traceback.print_exc()
        return 1

    print("\nALL PHASE 11 LIVE ACCEPTANCE CHECKS PASSED!", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
