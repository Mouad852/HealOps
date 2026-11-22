package com.example.Openstack_ai_agent.remediation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request body for submitting an operator approval decision.
 * Intentionally omits any actor/approvedBy field: actor identity is always derived
 * strictly from Spring Security's {@link org.springframework.security.core.Authentication}.
 */
public record SubmitApprovalDecisionRequest(
        @NotNull ApprovalDecisionType decision,
        @Size(max = 1000) String reason,
        @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max = 200) String idempotencyKey
) {
}
