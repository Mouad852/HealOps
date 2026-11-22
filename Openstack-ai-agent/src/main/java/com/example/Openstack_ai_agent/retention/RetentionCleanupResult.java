package com.example.Openstack_ai_agent.retention;

import java.time.OffsetDateTime;

public record RetentionCleanupResult(
        int deletedDashboardEvents,
        int deletedTelemetryReferences,
        int deletedConversationMessages,
        int deletedConversationSessions,
        OffsetDateTime executedAt
) {
    public int totalDeleted() {
        return deletedDashboardEvents
                + deletedTelemetryReferences
                + deletedConversationMessages
                + deletedConversationSessions;
    }
}
