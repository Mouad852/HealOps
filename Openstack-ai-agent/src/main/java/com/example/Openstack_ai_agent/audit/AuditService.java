package com.example.Openstack_ai_agent.audit;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Append-only security and remediation audit service.
 * Persists audit records in {@link Propagation#REQUIRES_NEW} so security denials (HTTP 403)
 * and failed operations are durably recorded even if the caller transaction rolls back.
 */
@Service
public class AuditService {
    private static final int MAX_ACTOR_LENGTH = 100;
    private static final int MAX_OPERATION_LENGTH = 100;
    private static final int MAX_TARGET_LENGTH = 200;
    private static final int MAX_TRACE_LENGTH = 128;
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|authorization|password|secret|token)(\\s*[:=]\\s*)([^\\s,;]+)"
    );

    private final AuditRecordRepository auditRecordRepository;

    public AuditService(AuditRecordRepository auditRecordRepository) {
        this.auditRecordRepository = auditRecordRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditRecordEntity record(
            Authentication authentication,
            String operation,
            String targetResource,
            AuditOutcome outcome,
            Map<String, Object> details,
            String traceId
    ) {
        return record(resolveActor(authentication, "system"), operation, targetResource, outcome, details, traceId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditRecordEntity record(
            String actor,
            String operation,
            String targetResource,
            AuditOutcome outcome,
            Map<String, Object> details,
            String traceId
    ) {
        String safeActor = truncate(actor == null || actor.isBlank() ? "system" : actor.trim(), MAX_ACTOR_LENGTH);
        String safeOperation = truncate(operation == null || operation.isBlank() ? "UNKNOWN" : operation.trim(), MAX_OPERATION_LENGTH);
        String safeTarget = truncate(targetResource == null || targetResource.isBlank() ? "unknown" : targetResource.trim(), MAX_TARGET_LENGTH);
        String safeTraceId = traceId == null || traceId.isBlank() ? null : truncate(traceId.trim(), MAX_TRACE_LENGTH);

        AuditRecordEntity entity = AuditRecordEntity.create(
                safeActor,
                safeOperation,
                safeTarget,
                outcome,
                sanitizeDetails(details),
                safeTraceId
        );
        return auditRecordRepository.saveAndFlush(entity);
    }

    @Transactional(readOnly = true)
    public List<AuditRecordEntity> findByTargetResource(String targetResource) {
        return auditRecordRepository.findByTargetResourceOrderByOccurredAtAsc(targetResource);
    }

    @Transactional(readOnly = true)
    public List<AuditRecordEntity> findRecent() {
        return auditRecordRepository.findAllByOrderByOccurredAtDesc();
    }

    public static String resolveActor(Authentication authentication, String fallback) {
        Authentication effective = authentication != null
                ? authentication
                : SecurityContextHolder.getContext().getAuthentication();
        if (effective == null
                || !effective.isAuthenticated()
                || effective instanceof AnonymousAuthenticationToken
                || effective.getName() == null
                || effective.getName().isBlank()) {
            return fallback;
        }
        return effective.getName();
    }

    private Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lowerKey.contains("password") || lowerKey.contains("secret") || lowerKey.contains("token") || lowerKey.contains("api_key")) {
                sanitized.put(key, "[REDACTED]");
            } else if (value instanceof String str) {
                String redacted = SENSITIVE_VALUE.matcher(str).replaceAll("$1$2[REDACTED]");
                sanitized.put(key, truncate(redacted, 1000));
            } else {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
