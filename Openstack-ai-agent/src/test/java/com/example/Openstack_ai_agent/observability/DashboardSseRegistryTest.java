package com.example.Openstack_ai_agent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardSseRegistryTest {

    @Test
    void replaysMissedEventsWithoutDuplicatingAlreadySentSequenceNumbersAndIsolatesEnvironments() {
        DashboardSseProperties props = testProperties();
        List<RecordingSseEmitter> created = new ArrayList<>();
        DashboardSseRegistry registry = new DashboardSseRegistry(props) {
            @Override
            protected SseEmitter createEmitter(long timeoutMs) {
                RecordingSseEmitter emitter = new RecordingSseEmitter(timeoutMs);
                created.add(emitter);
                return emitter;
            }
        };

        DashboardEventEnvelope seq2 = envelope("local", 2L, "incident.updated");
        DashboardEventEnvelope seq3 = envelope("local", 3L, "approval.created");
        DashboardEventEnvelope dup3 = envelope("local", 3L, "approval.created");
        DashboardEventEnvelope staging4 = envelope("staging", 4L, "incident.updated");
        DashboardEventEnvelope live5 = envelope("local", 5L, "tool-execution.updated");

        registry.registerAndReplay("local", 1L, () -> List.of(seq2, seq3));
        assertThat(registry.activeConnectionCount("local")).isEqualTo(1);

        // Duplicate sequence 3 and cross-environment event 4 must be ignored; live event 5 delivered
        registry.broadcast(dup3);
        registry.broadcast(staging4);
        registry.broadcast(live5);

        RecordingSseEmitter emitter = created.getFirst();
        // 1 initial connected comment + seq2 + seq3 + live5 = 4 sends
        assertThat(emitter.sentCount).isEqualTo(4);

        registry.completeEnvironment("local");
        assertThat(registry.activeConnectionCount("local")).isZero();
    }

    @Test
    void evictsSlowOrDisconnectedClientsOnSendFailureAndHeartbeatFailure() {
        DashboardSseProperties props = testProperties();
        List<RecordingSseEmitter> created = new ArrayList<>();
        DashboardSseRegistry registry = new DashboardSseRegistry(props) {
            @Override
            protected SseEmitter createEmitter(long timeoutMs) {
                RecordingSseEmitter emitter = new RecordingSseEmitter(timeoutMs);
                created.add(emitter);
                return emitter;
            }
        };

        registry.registerAndReplay("local", null, List::of);
        assertThat(registry.totalActiveConnections()).isEqualTo(1);

        // Simulate broken pipe on next send (heartbeat)
        created.getFirst().failNextSend = true;
        registry.sendHeartbeats();

        assertThat(registry.totalActiveConnections()).isZero();
        assertThat(created.getFirst().completedWithError).isTrue();
    }

    @Test
    void envelopeExtractsEntityVersionAndPreservesSchemaVersion() {
        DashboardEventEntity entity = DashboardEventEntity.of(
                "local",
                "incident.updated",
                UUID.randomUUID(),
                "trace-123",
                Map.of("version", 7, "status", "DIAGNOSING")
        );
        DashboardEventEnvelope env = DashboardEventEnvelope.from(entity);
        assertThat(env.schemaVersion()).isEqualTo("v1");
        assertThat(env.entityVersion()).isEqualTo(7L);
        assertThat(env.type()).isEqualTo("incident.updated");
        assertThat(env.environmentId()).isEqualTo("local");
    }

    private DashboardSseProperties testProperties() {
        DashboardSseProperties props = new DashboardSseProperties();
        props.setHeartbeatEnabled(false);
        props.setHeartbeatInterval(Duration.ZERO);
        props.setEmitterTimeout(Duration.ofSeconds(30));
        return props;
    }

    private DashboardEventEnvelope envelope(String env, long seq, String type) {
        return new DashboardEventEnvelope(
                DashboardEventEnvelope.SCHEMA_VERSION_V1,
                UUID.randomUUID(),
                seq,
                type,
                OffsetDateTime.now(),
                env,
                UUID.randomUUID(),
                "trace-test",
                1L,
                Map.of("seq", seq)
        );
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        int sentCount;
        boolean failNextSend;
        boolean completedWithError;

        RecordingSseEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failNextSend) {
                throw new IOException("Broken pipe");
            }
            sentCount++;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError = true;
            super.completeWithError(ex);
        }
    }
}
