package com.example.Openstack_ai_agent.observability;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ObservabilityController {
    private final MetricsQueryService metricsQueryService;
    private final LogsQueryService logsQueryService;
    private final TracesQueryService tracesQueryService;
    private final TopologyQueryService topologyQueryService;

    public ObservabilityController(
            MetricsQueryService metricsQueryService,
            LogsQueryService logsQueryService,
            TracesQueryService tracesQueryService,
            TopologyQueryService topologyQueryService
    ) {
        this.metricsQueryService = metricsQueryService;
        this.logsQueryService = logsQueryService;
        this.tracesQueryService = tracesQueryService;
        this.topologyQueryService = topologyQueryService;
    }

    @GetMapping("/metrics/range")
    @PreAuthorize("hasAuthority('incident:read')")
    public MetricRangeResponse getMetricsRange(
            @RequestParam String environmentId,
            @RequestParam String service,
            @RequestParam String metric,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer stepSeconds,
            Authentication authentication
    ) {
        return metricsQueryService.queryRange(environmentId, service, metric, from, to, stepSeconds, authentication);
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('incident:read')")
    public LogQueryResponse getLogs(
            @RequestParam String environmentId,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) UUID incidentId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer limit,
            Authentication authentication
    ) {
        return logsQueryService.queryLogs(environmentId, service, incidentId, traceId, from, to, limit, authentication);
    }

    @GetMapping("/traces")
    @PreAuthorize("hasAuthority('incident:read')")
    public TraceResponses.TraceListResponse getTraces(
            @RequestParam String environmentId,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer limit,
            Authentication authentication
    ) {
        return tracesQueryService.listTraces(environmentId, service, from, to, limit, authentication);
    }

    @GetMapping("/traces/{traceId}")
    @PreAuthorize("hasAuthority('incident:read')")
    public TraceResponses.TraceDetailResponse getTrace(
            @PathVariable String traceId,
            @RequestParam String environmentId,
            Authentication authentication
    ) {
        return tracesQueryService.getTrace(traceId, environmentId, authentication);
    }

    @GetMapping("/topology")
    @PreAuthorize("hasAuthority('incident:read')")
    public TopologyResponse getTopology(
            @RequestParam String environmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            Authentication authentication
    ) {
        return topologyQueryService.getTopology(environmentId, from, to, authentication);
    }
}
