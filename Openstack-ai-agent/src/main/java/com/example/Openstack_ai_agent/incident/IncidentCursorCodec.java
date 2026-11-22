package com.example.Openstack_ai_agent.incident;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

public final class IncidentCursorCodec {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private IncidentCursorCodec() {
    }

    public static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    public static String encode(OffsetDateTime createdAt, UUID id) {
        if (createdAt == null || id == null) {
            throw new IllegalArgumentException("createdAt and id are required to encode a pagination cursor");
        }
        String raw = createdAt.toInstant().toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static CursorToken decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor.trim());
            String raw = new String(decoded, StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator <= 0 || separator >= raw.length() - 1 || raw.indexOf('|', separator + 1) != -1) {
                throw new IllegalArgumentException("Invalid pagination cursor");
            }
            Instant createdAt = Instant.parse(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new CursorToken(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid pagination cursor");
        }
    }

    public record CursorToken(Instant createdAt, UUID id) {
    }
}
