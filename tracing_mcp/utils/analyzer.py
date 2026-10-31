# utils/analyzer.py
import statistics


def analyze_trace(trace):
    if not trace:
        return "Trace not found."

    spans = trace.get("spans", [])
    durations = [s.get("duration", 0) / 1000 for s in spans]

    errors = [
        s for s in spans
        if any(tag.get("key") == "error" and tag.get("value") for tag in s.get("tags", []))
    ]

    msg = []
    msg.append(f"Trace ID: {trace.get('traceID')}")
    msg.append(f"Total spans: {len(spans)}")

    if durations:
        msg.append(f"Avg duration: {statistics.mean(durations):.2f} ms")
        msg.append(f"Max duration: {max(durations):.2f} ms")

    msg.append(f"Error spans: {len(errors)}")
    return "\n".join(msg)


def summarize_health(service, traces):
    total = len(traces)
    if total == 0:
        return f"No traces found for service: {service}"

    errors = 0
    for trace in traces:
        for span in trace.get("spans", []):
            if any(tag.get("key") == "error" and tag.get("value") for tag in span.get("tags", [])):
                errors += 1

    rate = (errors / total) * 100
    return f"Service: {service}\nTraces: {total}\nError rate: {rate:.2f}%"
