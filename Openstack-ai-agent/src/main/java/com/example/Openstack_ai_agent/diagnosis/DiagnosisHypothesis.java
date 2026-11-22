package com.example.Openstack_ai_agent.diagnosis;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DiagnosisHypothesis(
        String title,
        String description,
        BigDecimal confidence,
        List<UUID> evidenceIds,
        String ruleCode
) {
}
