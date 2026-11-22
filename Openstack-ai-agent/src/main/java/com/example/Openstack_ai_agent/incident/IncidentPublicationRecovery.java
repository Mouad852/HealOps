package com.example.Openstack_ai_agent.incident;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Component
public class IncidentPublicationRecovery {
    private static final Logger log = LoggerFactory.getLogger(IncidentPublicationRecovery.class);

    private final IncompleteEventPublications incompleteEventPublications;
    private final JdbcTemplate jdbcTemplate;
    private final boolean autoOrchestrate;

    public IncidentPublicationRecovery(
            IncompleteEventPublications incompleteEventPublications,
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            @Value("${healops.workflow.auto-orchestrate:true}") boolean autoOrchestrate
    ) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.jdbcTemplate = jdbcTemplate;
        this.autoOrchestrate = autoOrchestrate;

        Gauge.builder("healops.workflow.incomplete_publications", this, IncidentPublicationRecovery::countIncomplete)
                .description("Number of incomplete durable incident workflow event publications")
                .register(meterRegistry);

        Gauge.builder("healops.workflow.oldest_incomplete_publication_seconds", this, IncidentPublicationRecovery::oldestIncompleteAgeSeconds)
                .description("Age in seconds of the oldest incomplete workflow event publication")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeIncompletePublicationsOnStartup() {
        if (!autoOrchestrate) {
            return;
        }
        long pending = (long) countIncomplete();
        if (pending > 0) {
            log.info("Resuming {} incomplete Spring Modulith event publication(s) on startup", pending);
            incompleteEventPublications.resubmitIncompletePublicationsOlderThan(Duration.ZERO);
        }
    }

    public double countIncomplete() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is null",
                Long.class
        );
        return count == null ? 0.0 : count.doubleValue();
    }

    public double oldestIncompleteAgeSeconds() {
        Timestamp oldest = jdbcTemplate.queryForObject(
                "select min(publication_date) from event_publication where completion_date is null",
                Timestamp.class
        );
        if (oldest == null) {
            return 0.0;
        }
        return Math.max(0L, Duration.between(oldest.toInstant(), Instant.now()).toSeconds());
    }
}
