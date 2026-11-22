package com.example.Openstack_ai_agent.observability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LogQueryResponse(
        String environmentId,
        OffsetDateTime from,
        OffsetDateTime to,
        int limit,
        boolean truncated,
        String dataSource,
        List<LogRecordDto> records
) {
    public LogQueryResponse {
        records = records == null ? List.of() : List.copyOf(records);
    }

    public record LogRecordDto(
            OffsetDateTime timestamp,
            String service,
            String level,
            String message,
            String traceId,
            UUID incidentId,
            String source,
            boolean redacted,
            Map<String, String> attributes
    ) {
        public LogRecordDto {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
