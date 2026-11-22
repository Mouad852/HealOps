package com.example.Openstack_ai_agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TraceAnalysisCalculator {
    private static final int DEFAULT_SLOWEST_SPAN_COUNT = 5;

    public List<RawSpan> parseJaegerV3OtlpSpans(JsonNode root, int maxSpans) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        List<RawSpan> spans = new ArrayList<>();
        // Jaeger v3 /api/v3/traces returns either { "result": { "resourceSpans": [...] } } or { "resourceSpans": [...] }
        JsonNode resourceSpans = root.has("resourceSpans")
                ? root.path("resourceSpans")
                : root.path("result").path("resourceSpans");

        if (resourceSpans.isArray()) {
            extractResourceSpans(resourceSpans, spans, maxSpans);
        } else if (root.isArray()) {
            for (JsonNode item : root) {
                JsonNode nested = item.has("resourceSpans")
                        ? item.path("resourceSpans")
                        : item.path("result").path("resourceSpans");
                if (nested.isArray()) {
                    extractResourceSpans(nested, spans, maxSpans);
                }
            }
        }
        return List.copyOf(spans);
    }

    public TraceResponses.TraceDetailResponse buildDetail(
            String traceId,
            String environmentId,
            String dataSource,
            List<RawSpan> rawSpans
    ) {
        if (rawSpans == null || rawSpans.isEmpty()) {
            throw new TraceNotFoundException(traceId);
        }
        Map<String, RawSpan> byId = new LinkedHashMap<>();
        Map<String, List<RawSpan>> childrenByParent = new HashMap<>();
        for (RawSpan span : rawSpans) {
            byId.put(span.spanId(), span);
        }

        List<RawSpan> roots = new ArrayList<>();
        for (RawSpan span : rawSpans) {
            if (span.parentSpanId() == null || span.parentSpanId().isBlank() || !byId.containsKey(span.parentSpanId())) {
                roots.add(span);
            } else {
                childrenByParent.computeIfAbsent(span.parentSpanId(), ignored -> new ArrayList<>()).add(span);
            }
        }
        roots.sort(Comparator.comparing(RawSpan::startedAt));

        Set<String> visited = new LinkedHashSet<>();
        List<TraceResponses.TraceSpanNode> spanTree = new ArrayList<>();
        for (RawSpan root : roots) {
            spanTree.add(buildTreeNode(root, childrenByParent, visited));
        }

        List<TraceResponses.TraceSpanSummary> criticalPath = computeCriticalPath(spanTree);
        List<TraceResponses.TraceSpanSummary> slowestSpans = rawSpans.stream()
                .sorted(Comparator.comparingDouble(RawSpan::durationMs).reversed())
                .limit(DEFAULT_SLOWEST_SPAN_COUNT)
                .map(this::toSummary)
                .toList();

        RawSpan primaryRoot = roots.isEmpty() ? rawSpans.getFirst() : roots.getFirst();
        OffsetDateTime minStart = rawSpans.stream()
                .map(RawSpan::startedAt)
                .min(Comparator.naturalOrder())
                .orElse(primaryRoot.startedAt());
        double totalDurationMs = rawSpans.stream()
                .mapToDouble(RawSpan::durationMs)
                .max()
                .orElse(0.0);
        int errorSpanCount = (int) rawSpans.stream()
                .filter(s -> "ERROR".equalsIgnoreCase(s.status()))
                .count();

        return new TraceResponses.TraceDetailResponse(
                traceId,
                environmentId,
                primaryRoot.service(),
                primaryRoot.operation(),
                minStart,
                roundTwoDecimals(totalDurationMs),
                rawSpans.size(),
                errorSpanCount,
                dataSource,
                spanTree,
                criticalPath,
                slowestSpans
        );
    }

    public List<TraceResponses.TraceSummaryDto> summarizeByTrace(List<RawSpan> allSpans) {
        if (allSpans == null || allSpans.isEmpty()) {
            return List.of();
        }
        Map<String, List<RawSpan>> grouped = new LinkedHashMap<>();
        for (RawSpan span : allSpans) {
            grouped.computeIfAbsent(span.traceId(), ignored -> new ArrayList<>()).add(span);
        }

        List<TraceResponses.TraceSummaryDto> summaries = new ArrayList<>();
        for (Map.Entry<String, List<RawSpan>> entry : grouped.entrySet()) {
            List<RawSpan> spans = entry.getValue();
            RawSpan root = spans.stream()
                    .filter(s -> s.parentSpanId() == null || s.parentSpanId().isBlank())
                    .min(Comparator.comparing(RawSpan::startedAt))
                    .orElseGet(() -> spans.stream().min(Comparator.comparing(RawSpan::startedAt)).orElse(spans.getFirst()));
            double maxDuration = spans.stream().mapToDouble(RawSpan::durationMs).max().orElse(0.0);
            int errors = (int) spans.stream().filter(s -> "ERROR".equalsIgnoreCase(s.status())).count();
            List<String> services = spans.stream().map(RawSpan::service).distinct().sorted().toList();
            summaries.add(new TraceResponses.TraceSummaryDto(
                    entry.getKey(),
                    root.service(),
                    root.operation(),
                    root.startedAt(),
                    roundTwoDecimals(maxDuration),
                    spans.size(),
                    errors,
                    services,
                    null
            ));
        }
        summaries.sort(Comparator.comparing(TraceResponses.TraceSummaryDto::startedAt).reversed());
        return List.copyOf(summaries);
    }

    private TraceResponses.TraceSpanNode buildTreeNode(
            RawSpan span,
            Map<String, List<RawSpan>> childrenByParent,
            Set<String> visited
    ) {
        if (!visited.add(span.spanId())) {
            return new TraceResponses.TraceSpanNode(
                    span.spanId(),
                    span.parentSpanId(),
                    span.service(),
                    span.operation(),
                    span.startedAt(),
                    roundTwoDecimals(span.durationMs()),
                    span.status(),
                    span.attributes(),
                    List.of()
            );
        }
        List<RawSpan> directChildren = new ArrayList<>(childrenByParent.getOrDefault(span.spanId(), List.of()));
        directChildren.sort(Comparator.comparing(RawSpan::startedAt));
        List<TraceResponses.TraceSpanNode> childNodes = new ArrayList<>();
        for (RawSpan child : directChildren) {
            childNodes.add(buildTreeNode(child, childrenByParent, visited));
        }
        return new TraceResponses.TraceSpanNode(
                span.spanId(),
                span.parentSpanId(),
                span.service(),
                span.operation(),
                span.startedAt(),
                roundTwoDecimals(span.durationMs()),
                span.status(),
                span.attributes(),
                childNodes
        );
    }

    private List<TraceResponses.TraceSpanSummary> computeCriticalPath(List<TraceResponses.TraceSpanNode> roots) {
        List<TraceResponses.TraceSpanSummary> best = List.of();
        double bestWeight = -1.0;
        for (TraceResponses.TraceSpanNode root : roots) {
            WeightedPath candidate = longestPathFrom(root);
            if (candidate.totalDurationMs() > bestWeight) {
                bestWeight = candidate.totalDurationMs();
                best = candidate.path();
            }
        }
        return best;
    }

    private WeightedPath longestPathFrom(TraceResponses.TraceSpanNode node) {
        TraceResponses.TraceSpanSummary self = new TraceResponses.TraceSpanSummary(
                node.spanId(),
                node.parentSpanId(),
                node.service(),
                node.operation(),
                node.durationMs(),
                node.status()
        );
        if (node.children().isEmpty()) {
            return new WeightedPath(node.durationMs(), List.of(self));
        }
        WeightedPath heaviestChild = null;
        for (TraceResponses.TraceSpanNode child : node.children()) {
            WeightedPath candidate = longestPathFrom(child);
            if (heaviestChild == null || candidate.totalDurationMs() > heaviestChild.totalDurationMs()) {
                heaviestChild = candidate;
            }
        }
        List<TraceResponses.TraceSpanSummary> combined = new ArrayList<>();
        combined.add(self);
        if (heaviestChild != null) {
            combined.addAll(heaviestChild.path());
            return new WeightedPath(node.durationMs() + heaviestChild.totalDurationMs(), List.copyOf(combined));
        }
        return new WeightedPath(node.durationMs(), List.copyOf(combined));
    }

    private void extractResourceSpans(JsonNode resourceSpans, List<RawSpan> target, int maxSpans) {
        for (JsonNode rs : resourceSpans) {
            String serviceName = extractServiceName(rs.path("resource").path("attributes"));
            JsonNode scopeSpans = rs.path("scopeSpans");
            if (!scopeSpans.isArray()) {
                continue;
            }
            for (JsonNode scopeSpan : scopeSpans) {
                JsonNode spansNode = scopeSpan.path("spans");
                if (!spansNode.isArray()) {
                    continue;
                }
                for (JsonNode spanNode : spansNode) {
                    if (target.size() >= maxSpans) {
                        return;
                    }
                    String traceId = spanNode.path("traceId").asText("");
                    String spanId = spanNode.path("spanId").asText("");
                    if (traceId.isBlank() || spanId.isBlank()) {
                        continue;
                    }
                    String parentSpanId = spanNode.path("parentSpanId").asText(null);
                    if (parentSpanId != null && parentSpanId.isBlank()) {
                        parentSpanId = null;
                    }
                    String operation = spanNode.path("name").asText("unknown");
                    long startNanos = parseLongSafe(spanNode.path("startTimeUnixNano").asText("0"));
                    long endNanos = parseLongSafe(spanNode.path("endTimeUnixNano").asText("0"));
                    double durationMs = endNanos > startNanos ? (endNanos - startNanos) / 1_000_000.0 : 0.0;
                    OffsetDateTime startedAt = startNanos > 0
                            ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(startNanos / 1_000_000_000L, startNanos % 1_000_000_000L), ZoneOffset.UTC)
                            : OffsetDateTime.now(ZoneOffset.UTC);
                    String status = resolveOtlpStatus(spanNode.path("status"));
                    Map<String, String> attrs = extractAttributes(spanNode.path("attributes"));
                    target.add(new RawSpan(
                            traceId,
                            spanId,
                            parentSpanId,
                            serviceName,
                            operation,
                            startedAt,
                            roundTwoDecimals(durationMs),
                            status,
                            attrs
                    ));
                }
            }
        }
    }

    private String extractServiceName(JsonNode attributes) {
        if (attributes.isArray()) {
            for (JsonNode attr : attributes) {
                if ("service.name".equals(attr.path("key").asText())) {
                    String val = attr.path("value").path("stringValue").asText("");
                    if (!val.isBlank()) {
                        return val;
                    }
                }
            }
        }
        return "unknown";
    }

    private Map<String, String> extractAttributes(JsonNode attributes) {
        if (!attributes.isArray()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (JsonNode attr : attributes) {
            String key = attr.path("key").asText("");
            if (key.isBlank() || map.size() >= 25) {
                continue;
            }
            JsonNode val = attr.path("value");
            if (val.has("stringValue")) {
                map.put(key, val.path("stringValue").asText());
            } else if (val.has("intValue")) {
                map.put(key, val.path("intValue").asText());
            } else if (val.has("doubleValue")) {
                map.put(key, val.path("doubleValue").asText());
            } else if (val.has("boolValue")) {
                map.put(key, Boolean.toString(val.path("boolValue").asBoolean()));
            }
        }
        return map;
    }

    private String resolveOtlpStatus(JsonNode statusNode) {
        if (statusNode == null || statusNode.isMissingNode()) {
            return "OK";
        }
        JsonNode code = statusNode.path("code");
        if (code.isInt()) {
            return code.asInt() == 2 ? "ERROR" : "OK";
        }
        String text = code.asText("");
        if ("STATUS_CODE_ERROR".equalsIgnoreCase(text) || "ERROR".equalsIgnoreCase(text) || "2".equals(text)) {
            return "ERROR";
        }
        return "OK";
    }

    private TraceResponses.TraceSpanSummary toSummary(RawSpan span) {
        return new TraceResponses.TraceSpanSummary(
                span.spanId(),
                span.parentSpanId(),
                span.service(),
                span.operation(),
                roundTwoDecimals(span.durationMs()),
                span.status()
        );
    }

    private long parseLongSafe(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record RawSpan(
            String traceId,
            String spanId,
            String parentSpanId,
            String service,
            String operation,
            OffsetDateTime startedAt,
            double durationMs,
            String status,
            Map<String, String> attributes
    ) {
        public RawSpan {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    private record WeightedPath(double totalDurationMs, List<TraceResponses.TraceSpanSummary> path) {
    }
}
