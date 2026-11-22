package com.example.Openstack_ai_agent.evidence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class EvidencePersistenceService {
    private static final int MAX_EXCERPT_LENGTH = 8192;
    private static final int MAX_METADATA_ENTRIES = 50;
    private static final int MAX_METADATA_VALUE_LENGTH = 512;
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|authorization|password|secret|token)(\\s*[:=]\\s*)([^\\s,;]+)"
    );

    private final EvidenceItemRepository evidenceItemRepository;

    public EvidencePersistenceService(EvidenceItemRepository evidenceItemRepository) {
        this.evidenceItemRepository = evidenceItemRepository;
    }

    @Transactional
    public EvidenceItemResponse record(UUID incidentId, EvidenceCandidate candidate) {
        validate(candidate);
        SanitizedValue sanitizedExcerpt = sanitize(candidate.excerpt(), MAX_EXCERPT_LENGTH);
        Map<String, String> metadata = sanitizeMetadata(candidate.metadata());
        RedactionStatus redactionStatus = sanitizedExcerpt.redacted() || metadata.values().stream()
                .anyMatch(value -> value.contains("[REDACTED]"))
                ? RedactionStatus.REDACTED
                : RedactionStatus.NOT_REQUIRED;

        EvidenceItemEntity item = EvidenceItemEntity.from(
                incidentId,
                candidate,
                sanitizedExcerpt.value(),
                redactionStatus,
                sha256(sanitizedExcerpt.value()),
                metadata
        );
        return EvidenceItemResponse.from(evidenceItemRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<EvidenceItemResponse> findAll(UUID incidentId) {
        return evidenceItemRepository.findByIncidentIdOrderByObservedAtDesc(incidentId).stream()
                .map(EvidenceItemResponse::from)
                .toList();
    }

    private void validate(EvidenceCandidate candidate) {
        if (candidate.type() == null || isBlank(candidate.source()) || isBlank(candidate.excerpt()) || candidate.observedAt() == null) {
            throw new IllegalArgumentException("Evidence candidates require type, source, observed time, and excerpt");
        }
    }

    private Map<String, String> sanitizeMetadata(Map<String, String> metadata) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        metadata.entrySet().stream().limit(MAX_METADATA_ENTRIES).forEach(entry -> {
            String key = truncate(entry.getKey(), 100);
            sanitized.put(key, sanitize(entry.getValue(), MAX_METADATA_VALUE_LENGTH).value());
        });
        return Map.copyOf(sanitized);
    }

    private SanitizedValue sanitize(String value, int maxLength) {
        String safeValue = value == null ? "" : value;
        String redacted = SENSITIVE_VALUE.matcher(safeValue).replaceAll("$1$2[REDACTED]");
        return new SanitizedValue(truncate(redacted, maxLength), !redacted.equals(safeValue));
    }

    private String truncate(String value, int maxLength) {
        String safeValue = value == null ? "" : value;
        return safeValue.length() <= maxLength ? safeValue : safeValue.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SanitizedValue(String value, boolean redacted) {
    }
}
