import statistics


def _is_error_span(span) -> bool:
    if span.get("error") is True:
        return True
    return any(
        tag.get("key") == "error" and str(tag.get("value")).lower() == "true"
        for tag in span.get("tags", [])
    )


def analyze_trace(trace):
    if not trace:
        return "Trace not found."

    spans = trace.get("spans", [])
    durations = [s.get("duration", 0) / 1000 for s in spans]
    errors = [s for s in spans if _is_error_span(s)]

    msg = [
        f"Trace ID: {trace.get('traceID')}",
        f"Total spans: {len(spans)}",
    ]

    if durations:
        msg.append(f"Avg duration: {statistics.mean(durations):.2f} ms")
        msg.append(f"Max duration: {max(durations):.2f} ms")

    msg.append(f"Error spans: {len(errors)}")
    return "\n".join(msg)


def summarize_health(service, traces):
    total = len(traces)
    if total == 0:
        return f"No traces found for service: {service}"

    error_traces = sum(
        1 for trace in traces if any(_is_error_span(span) for span in trace.get("spans", []))
    )
    rate = (error_traces / total) * 100
    return f"Service: {service}\nTraces: {total}\nError rate: {rate:.2f}%"
