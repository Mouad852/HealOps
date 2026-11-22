package com.example.Openstack_ai_agent.conversation;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Redacts credential-like patterns and bounds conversation text so raw secrets
 * and unbounded telemetry payloads are never persisted in conversation tables.
 */
@Component
public class ConversationSanitizer {
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_CONTENT_LENGTH = 4000;

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"
    );
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|authorization|password|passwd|secret|client[_-]?secret|access[_-]?token|refresh[_-]?token|token)(\\s*[:=]\\s*)([^\\s,;\"']+)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(bearer\\s+)([A-Za-z0-9._~+/=-]{8,})"
    );

    public String sanitizeTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "Operator conversation";
        }
        String sanitized = redact(rawTitle).replace('\r', ' ').replace('\n', ' ').trim();
        if (sanitized.isBlank()) {
            return "Operator conversation";
        }
        return truncate(sanitized, MAX_TITLE_LENGTH);
    }

    public String sanitizeContent(String rawContent) {
        if (rawContent == null) {
            return "";
        }
        String sanitized = redact(rawContent).trim();
        return truncate(sanitized, MAX_CONTENT_LENGTH);
    }

    public String redact(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String step1 = PRIVATE_KEY_BLOCK.matcher(raw).replaceAll("[REDACTED_PRIVATE_KEY]");
        String step2 = KEY_VALUE_SECRET.matcher(step1).replaceAll("$1$2[REDACTED]");
        return BEARER_TOKEN.matcher(step2).replaceAll("$1[REDACTED]");
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String suffix = " [truncated]";
        int cutoff = Math.max(0, maxLength - suffix.length());
        return value.substring(0, cutoff) + suffix;
    }
}
