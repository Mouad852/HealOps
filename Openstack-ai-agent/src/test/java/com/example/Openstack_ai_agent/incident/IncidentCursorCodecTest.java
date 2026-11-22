package com.example.Openstack_ai_agent.incident;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentCursorCodecTest {

    @Test
    void encodesAndDecodesCursorPreservingTimestampAndId() {
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 11, 22, 12, 30, 45, 123_456_789, ZoneOffset.UTC);
        UUID id = UUID.randomUUID();

        String encoded = IncidentCursorCodec.encode(createdAt, id);
        IncidentCursorCodec.CursorToken decoded = IncidentCursorCodec.decode(encoded);

        assertThat(decoded).isNotNull();
        assertThat(decoded.createdAt()).isEqualTo(createdAt.toInstant());
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void resolvesDefaultAndValidLimitsAndRejectsAbusiveLimits() {
        assertThat(IncidentCursorCodec.resolveLimit(null)).isEqualTo(IncidentCursorCodec.DEFAULT_LIMIT);
        assertThat(IncidentCursorCodec.resolveLimit(1)).isEqualTo(1);
        assertThat(IncidentCursorCodec.resolveLimit(100)).isEqualTo(100);

        assertThatThrownBy(() -> IncidentCursorCodec.resolveLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");
        assertThatThrownBy(() -> IncidentCursorCodec.resolveLimit(-10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IncidentCursorCodec.resolveLimit(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedOrCorruptCursors() {
        assertThat(IncidentCursorCodec.decode(null)).isNull();
        assertThat(IncidentCursorCodec.decode("   ")).isNull();

        assertThatThrownBy(() -> IncidentCursorCodec.decode("not-valid-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid pagination cursor");
        assertThatThrownBy(() -> IncidentCursorCodec.decode("aGVsbG8")) // "hello" without separator
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid pagination cursor");
    }
}
