package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisValidationServiceTest {

    private final ActionCatalogValidator actionCatalogValidator = new ActionCatalogValidator();
    private final DiagnosisValidationService validationService = new DiagnosisValidationService(actionCatalogValidator);

    @Test
    void acceptsValidStructuredDiagnosisWithPersistedEvidenceCitationsAndAllowlistedAction() {
        UUID ev1 = UUID.randomUUID();
        UUID ev2 = UUID.randomUUID();

        LlmDiagnosisDraft draft = new LlmDiagnosisDraft(
                "Checkout workload is OOMKilled under elevated traffic.",
                new BigDecimal("0.88"),
                List.of(new LlmDiagnosisDraft.HypothesisDraft(
                        "Memory exhaustion causing CrashLoopBackOff",
                        "Container exited with OOMKilled and restarted repeatedly.",
                        new BigDecimal("0.88"),
                        List.of(ev1.toString(), ev2.toString()),
                        "OOM_KILLED"
                )),
                List.of(),
                new LlmDiagnosisDraft.ActionRecommendationDraft(
                        "RESTART_K8S_WORKLOAD",
                        Map.of("namespace", "payments", "workload", "checkout-api", "kind", "Deployment")
                )
        );

        DiagnosisValidationService.ValidatedDiagnosis validated = validationService.validate(
                draft,
                Set.of(ev1, ev2),
                "gemini-3.5-flash-lite@diagnosis-prompt-v1",
                DeterministicDiagnosticsEngine.RULE_VERSION
        );

        assertThat(validated.citedEvidenceIds()).containsExactly(ev1, ev2);
        assertThat(validated.actionProposal().actionType()).isEqualTo(ActionType.RESTART_K8S_WORKLOAD);
        assertThat(validated.actionProposal().commandPreview())
                .isEqualTo("kubectl rollout restart deployment/checkout-api -n payments");
        assertThat(validated.confidence()).isEqualByComparingTo("0.88");
    }

    @Test
    void rejectsHypothesisCitingNonexistentEvidenceUuid() {
        UUID realEvidenceId = UUID.randomUUID();
        UUID hallucinatedEvidenceId = UUID.randomUUID();

        LlmDiagnosisDraft draft = new LlmDiagnosisDraft(
                "Hallucinated evidence citation",
                new BigDecimal("0.80"),
                List.of(new LlmDiagnosisDraft.HypothesisDraft(
                        "Database lock contention",
                        "Fabricated evidence reference.",
                        new BigDecimal("0.80"),
                        List.of(hallucinatedEvidenceId.toString()),
                        "LLM_SYNTHESIS"
                )),
                List.of(),
                new LlmDiagnosisDraft.ActionRecommendationDraft("NO_ACTION", Map.of("reason", "Investigate"))
        );

        assertThatThrownBy(() -> validationService.validate(
                draft,
                Set.of(realEvidenceId),
                "gemini-3.5-flash-lite@diagnosis-prompt-v1",
                DeterministicDiagnosticsEngine.RULE_VERSION
        ))
                .isInstanceOf(DiagnosisValidationException.class)
                .hasMessageContaining("nonexistent evidence ID")
                .hasMessageContaining(hallucinatedEvidenceId.toString());
    }

    @Test
    void rejectsHypothesisWithMissingCitationsOrInvalidUuidFormat() {
        UUID realEvidenceId = UUID.randomUUID();

        LlmDiagnosisDraft emptyCitations = new LlmDiagnosisDraft(
                "Summary without citations",
                new BigDecimal("0.70"),
                List.of(new LlmDiagnosisDraft.HypothesisDraft(
                        "Uncited root cause",
                        "No evidence IDs provided.",
                        new BigDecimal("0.70"),
                        List.of(),
                        "LLM_SYNTHESIS"
                )),
                List.of(),
                new LlmDiagnosisDraft.ActionRecommendationDraft("NO_ACTION", Map.of())
        );

        assertThatThrownBy(() -> validationService.validate(
                emptyCitations,
                Set.of(realEvidenceId),
                "model",
                "rules"
        ))
                .isInstanceOf(DiagnosisValidationException.class)
                .hasMessageContaining("must cite at least one persisted evidence ID");

        LlmDiagnosisDraft malformedUuid = new LlmDiagnosisDraft(
                "Summary with bad UUID",
                new BigDecimal("0.70"),
                List.of(new LlmDiagnosisDraft.HypothesisDraft(
                        "Bad citation",
                        "Not a UUID.",
                        new BigDecimal("0.70"),
                        List.of("not-a-uuid"),
                        "LLM_SYNTHESIS"
                )),
                List.of(),
                new LlmDiagnosisDraft.ActionRecommendationDraft("NO_ACTION", Map.of())
        );

        assertThatThrownBy(() -> validationService.validate(
                malformedUuid,
                Set.of(realEvidenceId),
                "model",
                "rules"
        ))
                .isInstanceOf(DiagnosisValidationException.class)
                .hasMessageContaining("invalid UUID");
    }

    @Test
    void rejectsUnsupportedActionsAndOutOfRangeParameters() {
        UUID realEvidenceId = UUID.randomUUID();

        LlmDiagnosisDraft unsupportedAction = new LlmDiagnosisDraft(
                "Run arbitrary command",
                new BigDecimal("0.90"),
                List.of(new LlmDiagnosisDraft.HypothesisDraft(
                        "Crash",
                        "Needs shell fix",
                        new BigDecimal("0.90"),
                        List.of(realEvidenceId.toString()),
                        "LLM_SYNTHESIS"
                )),
                List.of(),
                new LlmDiagnosisDraft.ActionRecommendationDraft("RUN_KUBECTL_EXEC", Map.of("command", "kill -9 1"))
        );

        assertThatThrownBy(() -> validationService.validate(
                unsupportedAction,
                Set.of(realEvidenceId),
                "model",
                "rules"
        ))
                .isInstanceOf(DiagnosisValidationException.class)
                .hasMessageContaining("Unsupported action type");

        LlmDiagnosisDraft outOfBoundsReplicas = new LlmDiagnosisDraft(
                "Scale massively",
                new BigDecimal("0.90"),
                List.of(new LlmDiagnosisDraft.HypothesisDraft(
                        "CPU saturation",
                        "High load",
                        new BigDecimal("0.90"),
                        List.of(realEvidenceId.toString()),
                        "CPU_SATURATION"
                )),
                List.of(),
                new LlmDiagnosisDraft.ActionRecommendationDraft(
                        "SCALE_K8S_DEPLOYMENT",
                        Map.of("namespace", "payments", "deployment", "checkout-api", "replicas", 50)
                )
        );

        assertThatThrownBy(() -> validationService.validate(
                outOfBoundsReplicas,
                Set.of(realEvidenceId),
                "model",
                "rules"
        ))
                .isInstanceOf(DiagnosisValidationException.class)
                .hasMessageContaining("out of allowed bounds");
    }

    @Test
    void llmPromptRedactsSecretsAndBoundsLongExcerpts() {
        LlmDiagnosisService llmService = new LlmDiagnosisService(mock(ChatClient.class), false, "gemini-test");
        DeterministicDiagnosticsEngine engine = new DeterministicDiagnosticsEngine();
        CorrelationService correlationService = new CorrelationService();

        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        IncidentResponse incident = new IncidentResponse(
                UUID.randomUUID(), "alertmanager", "a1", "fp1", "local",
                IncidentSeverity.HIGH, "checkout-api", IncidentStatus.DIAGNOSING,
                now, now, 3L, Map.of("namespace", "payments"), Map.of(), "trace-1"
        );
        String longLog = "password=super-secret-value token=abc12345 " + "x".repeat(2000);
        EvidenceItemResponse evidence = new EvidenceItemResponse(
                UUID.randomUUID(), EvidenceType.LOG, "loki", "checkout-api",
                now, now, now, "trace-1", longLog, "hash", RedactionStatus.NOT_REQUIRED, Map.of(), now
        );
        var correlation = correlationService.correlate(incident, List.of(evidence));
        var deterministic = engine.diagnose(incident, List.of(evidence), correlation);

        String prompt = llmService.buildBoundedUserPrompt(incident, List.of(evidence), correlation, deterministic);

        assertThat(prompt).doesNotContain("super-secret-value");
        assertThat(prompt).doesNotContain("abc12345");
        assertThat(prompt).contains("[REDACTED]");
        assertThat(prompt).contains(evidence.id().toString());
        assertThat(prompt.length()).isLessThan(2000);
    }

    @Test
    void incidentDiagnosisServiceFallsBackToDeterministicWhenLlmCitesHallucinatedEvidenceId() {
        DeterministicDiagnosticsEngine engine = new DeterministicDiagnosticsEngine();
        CorrelationService correlationService = new CorrelationService();
        HealthSnapshotService snapshotService = mock(HealthSnapshotService.class);
        LlmDiagnosisService llmService = mock(LlmDiagnosisService.class);
        DiagnosisPersistenceService persistenceService = mock(DiagnosisPersistenceService.class);

        IncidentDiagnosisService service = new IncidentDiagnosisService(
                engine, snapshotService, llmService, validationService, persistenceService
        );

        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        UUID incidentId = UUID.randomUUID();
        UUID realEvidenceId = UUID.randomUUID();
        UUID hallucinatedId = UUID.randomUUID();
        IncidentResponse incident = new IncidentResponse(
                incidentId, "alertmanager", "a1", "fp-fallback", "local",
                IncidentSeverity.HIGH, "checkout-api", IncidentStatus.DIAGNOSING,
                now, now, 3L, Map.of("namespace", "payments", "deployment", "checkout-api"), Map.of(), "trace-1"
        );
        EvidenceItemResponse evidence = new EvidenceItemResponse(
                realEvidenceId, EvidenceType.KUBERNETES_EVENT, "kubernetes", "checkout-api",
                now, now, now, "trace-1", "Pod CrashLoopBackOff restarts=5", "hash",
                RedactionStatus.NOT_REQUIRED, Map.of(), now
        );
        var correlation = correlationService.correlate(incident, List.of(evidence));

        when(snapshotService.captureBeforeSnapshot(eq(incident), any(), eq(correlation), any()))
                .thenReturn(new HealthSnapshotResponse(UUID.randomUUID(), incidentId, HealthSnapshotPhase.BEFORE, Map.of(), now));
        when(llmService.isEnabled()).thenReturn(true);
        when(llmService.modelVersion()).thenReturn("gemini-3.5-flash-lite@diagnosis-prompt-v1");
        when(llmService.generateDraft(eq(incident), any(), eq(correlation), any()))
                .thenReturn(new LlmDiagnosisDraft(
                        "Hallucinated root cause",
                        new BigDecimal("0.95"),
                        List.of(new LlmDiagnosisDraft.HypothesisDraft(
                                "Invented cause",
                                "Cites nonexistent evidence UUID",
                                new BigDecimal("0.95"),
                                List.of(hallucinatedId.toString()),
                                "LLM_SYNTHESIS"
                        )),
                        List.of(),
                        new LlmDiagnosisDraft.ActionRecommendationDraft("NO_ACTION", Map.of())
                ));
        when(persistenceService.persist(eq(incident), any())).thenAnswer(inv -> {
            DiagnosisValidationService.ValidatedDiagnosis v = inv.getArgument(1);
            DiagnosisResponse diag = new DiagnosisResponse(
                    UUID.randomUUID(), incidentId, v.summary(), v.hypotheses(),
                    v.confidence(), v.citedEvidenceIds(), v.modelVersion(), v.ruleVersion(), now
            );
            return new DiagnosisPersistenceService.PersistedDiagnosisResult(diag, null);
        });

        IncidentDiagnosisService.DiagnosisExecutionResult result =
                service.diagnoseAndPropose(incident, List.of(evidence), correlation);

        assertThat(result.diagnosis().modelVersion()).isEqualTo("deterministic-fallback:llm-rejected");
        assertThat(result.diagnosis().citedEvidenceIds()).containsExactly(realEvidenceId);
        verify(snapshotService).captureBeforeSnapshot(eq(incident), any(), eq(correlation), any());
        verify(persistenceService).persist(eq(incident), any());
    }
}
