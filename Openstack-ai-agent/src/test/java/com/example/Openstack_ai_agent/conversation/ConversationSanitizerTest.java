package com.example.Openstack_ai_agent.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSanitizerTest {

    private final ConversationSanitizer sanitizer = new ConversationSanitizer();

    @Test
    void redactsSecretsAndBoundsOversizedPayloads() {
        String raw = "Check logs with password=supersecret api_key:abc123456 Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig";
        String sanitized = sanitizer.sanitizeContent(raw);

        assertThat(sanitized)
                .doesNotContain("supersecret")
                .doesNotContain("abc123456")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("password=[REDACTED]")
                .contains("api_key:[REDACTED]")
                .contains("Bearer [REDACTED]");

        String oversized = "x".repeat(ConversationSanitizer.MAX_CONTENT_LENGTH + 500);
        String bounded = sanitizer.sanitizeContent(oversized);
        assertThat(bounded.length()).isLessThanOrEqualTo(ConversationSanitizer.MAX_CONTENT_LENGTH);
        assertThat(bounded).endsWith("[truncated]");
    }
}
