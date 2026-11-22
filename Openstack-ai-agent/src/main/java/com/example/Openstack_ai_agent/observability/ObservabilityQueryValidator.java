package com.example.Openstack_ai_agent.observability;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@Component
public class ObservabilityQueryValidator {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9._-]{1,128}$");
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[a-zA-Z0-9_-]{4,128}$");
    private static final int MIN_STEP_SECONDS = 1;
    private static final int MAX_STEP_SECONDS = 3600;
    private static final int DEFAULT_STEP_SECONDS = 60;

    private final ObservabilityQueryProperties properties;

    public ObservabilityQueryValidator(ObservabilityQueryProperties properties) {
        this.properties = properties;
    }

    public TimeRange resolveAndValidateRange(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime effectiveTo = to != null ? to : OffsetDateTime.now();
        OffsetDateTime effectiveFrom = from != null ? from : effectiveTo.minusHours(1);
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new IllegalArgumentException("'from' must be strictly before 'to'");
        }
        Duration maxWindow = properties.getMaxRangeWindow();
        Duration actualWindow = Duration.between(effectiveFrom, effectiveTo);
        if (actualWindow.compareTo(maxWindow) > 0) {
            throw new IllegalArgumentException(
                    "Requested time range exceeds maximum allowed window of " + maxWindow.toHours() + " hours"
            );
        }
        return new TimeRange(effectiveFrom, effectiveTo);
    }

    public String validateRequiredService(String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service is required");
        }
        return validateOptionalService(service);
    }

    public String validateOptionalService(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        String trimmed = service.trim();
        if (!SAFE_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid service identifier: must match [a-zA-Z0-9._-]{1,128}");
        }
        return trimmed;
    }

    public String validateTraceId(String traceId, boolean required) {
        if (traceId == null || traceId.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("traceId is required");
            }
            return null;
        }
        String trimmed = traceId.trim();
        if (!SAFE_TRACE_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid traceId format");
        }
        return trimmed;
    }

    public int resolveStepSeconds(Integer stepSeconds, TimeRange range) {
        int requested = stepSeconds != null ? stepSeconds : DEFAULT_STEP_SECONDS;
        if (requested < MIN_STEP_SECONDS || requested > MAX_STEP_SECONDS) {
            throw new IllegalArgumentException(
                    "stepSeconds must be between " + MIN_STEP_SECONDS + " and " + MAX_STEP_SECONDS
            );
        }
        return requested;
    }

    public int computeEffectiveStepSeconds(int requestedStepSeconds, TimeRange range) {
        long windowSeconds = Math.max(1L, Duration.between(range.from(), range.to()).getSeconds());
        int maxPoints = Math.max(10, properties.getMaxDataPoints());
        long minRequiredStep = (long) Math.ceil((double) windowSeconds / (double) maxPoints);
        return (int) Math.max(requestedStepSeconds, Math.max(1L, minRequiredStep));
    }

    public int resolveLogLimit(Integer limit) {
        int effective = limit != null ? limit : properties.getDefaultLogLimit();
        if (effective < 1 || effective > properties.getMaxLogLimit()) {
            throw new IllegalArgumentException("limit must be between 1 and " + properties.getMaxLogLimit());
        }
        return effective;
    }

    public int resolveTraceLimit(Integer limit) {
        int effective = limit != null ? limit : properties.getDefaultTraceLimit();
        if (effective < 1 || effective > properties.getMaxTraceLimit()) {
            throw new IllegalArgumentException("limit must be between 1 and " + properties.getMaxTraceLimit());
        }
        return effective;
    }

    public record TimeRange(OffsetDateTime from, OffsetDateTime to) {
    }
}
