package com.example.Openstack_ai_agent.runbook;

import com.example.Openstack_ai_agent.remediation.ActionCatalogValidationException;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionRiskLevel;
import com.example.Openstack_ai_agent.remediation.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunbookMatchingEngineTest {

    private final RunbookMatchingEngine matchingEngine = new RunbookMatchingEngine();
    private final ActionCatalogValidator actionCatalogValidator = new ActionCatalogValidator();

    @Test
    void ranksPublishedRunbooksByServiceLabelsFindingsActionAndPriorOutcomesAndSkipsDrafts() {
        RunbookEntity exactMatch = RunbookEntity.createDraft(
                "Checkout CrashLoop Recovery",
                Map.of(
                        "service", "checkout-api",
                        "environmentId", "local",
                        "targetLabels", Map.of("namespace", "payments", "deployment", "checkout-api"),
                        "findingCodes", List.of("K8S_CRASH_LOOP_BACKOFF"),
                        "actionType", "RESTART_K8S_WORKLOAD"
                )
        );
        exactMatch.markPublished();
        RunbookVersionEntity exactVersion = RunbookVersionEntity.create(
                exactMatch.getId(),
                1,
                List.of(new RunbookStep(
                        1,
                        ActionType.RESTART_K8S_WORKLOAD,
                        Map.of("namespace", "payments", "workload", "checkout-api", "kind", "Deployment"),
                        "Rollout restart deployment",
                        "kubectl rollout restart deployment/checkout-api -n payments",
                        ActionRiskLevel.MEDIUM,
                        "NON_REVERSIBLE_ROLLOUT_RESTART"
                )),
                Map.of("requiresApproval", true),
                "operator"
        );

        RunbookEntity partialMatch = RunbookEntity.createDraft(
                "Generic K8s Restart",
                Map.of(
                        "service", "other-service",
                        "findingCodes", List.of("K8S_CRASH_LOOP_BACKOFF"),
                        "actionType", "RESTART_K8S_WORKLOAD"
                )
        );
        partialMatch.markPublished();
        RunbookVersionEntity partialVersion = RunbookVersionEntity.create(
                partialMatch.getId(),
                1,
                List.of(new RunbookStep(
                        1,
                        ActionType.RESTART_K8S_WORKLOAD,
                        Map.of("namespace", "default", "workload", "other-service", "kind", "Deployment"),
                        "Generic restart",
                        "kubectl rollout restart deployment/other-service -n default",
                        ActionRiskLevel.MEDIUM,
                        "NON_REVERSIBLE_ROLLOUT_RESTART"
                )),
                Map.of("requiresApproval", true),
                "operator"
        );

        RunbookEntity draftRunbook = RunbookEntity.createDraft(
                "Unpublished Draft",
                Map.of("service", "checkout-api", "actionType", "RESTART_K8S_WORKLOAD")
        );
        RunbookVersionEntity draftVersion = RunbookVersionEntity.create(
                draftRunbook.getId(), 1, List.of(), Map.of("requiresApproval", true), "operator"
        );

        List<RunbookMatchingEngine.HistoricalOutcomeRecord> outcomes = List.of(
                new RunbookMatchingEngine.HistoricalOutcomeRecord("checkout-api", "local", ActionType.RESTART_K8S_WORKLOAD, true, false),
                new RunbookMatchingEngine.HistoricalOutcomeRecord("checkout-api", "local", ActionType.RESTART_K8S_WORKLOAD, true, false),
                new RunbookMatchingEngine.HistoricalOutcomeRecord("checkout-api", "local", ActionType.RESTART_K8S_WORKLOAD, false, false)
        );

        List<RunbookSuggestionResponse> suggestions = matchingEngine.rankSuggestions(
                new RunbookMatchingEngine.IncidentMatchContext(
                        "checkout-api",
                        "local",
                        Map.of("namespace", "payments", "deployment", "checkout-api"),
                        Set.of("K8S_CRASH_LOOP_BACKOFF"),
                        ActionType.RESTART_K8S_WORKLOAD
                ),
                List.of(
                        new RunbookMatchingEngine.PublishedRunbookCandidate(partialMatch, partialVersion),
                        new RunbookMatchingEngine.PublishedRunbookCandidate(draftRunbook, draftVersion),
                        new RunbookMatchingEngine.PublishedRunbookCandidate(exactMatch, exactVersion)
                ),
                outcomes
        );

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.getFirst().runbookId()).isEqualTo(exactMatch.getId());
        assertThat(suggestions.getFirst().matchScore()).isGreaterThan(suggestions.get(1).matchScore());
        assertThat(suggestions.getFirst().historicalOutcomeCount()).isEqualTo(3L);
        assertThat(suggestions.getFirst().historicalSuccessRate()).isEqualTo(0.6667);
        assertThat(suggestions.getFirst().readOnlyProposal()).isTrue();
        assertThat(suggestions.getFirst().requiresPolicyAndApproval()).isTrue();
        assertThat(suggestions.getFirst().matchedReasons())
                .contains("service=checkout-api", "environment=local", "finding:K8S_CRASH_LOOP_BACKOFF", "actionType=RESTART_K8S_WORKLOAD");
    }

    @Test
    void actionCatalogValidatorRejectsArbitraryShellStringsForRunbookSteps() {
        assertThatThrownBy(() -> actionCatalogValidator.validateAndNormalize(
                "SHELL_EXEC",
                Map.of("command", "rm -rf /")
        )).isInstanceOf(ActionCatalogValidationException.class);

        assertThatThrownBy(() -> actionCatalogValidator.validateAndNormalize(
                "RESTART_K8S_WORKLOAD",
                Map.of("namespace", "payments; rm -rf /", "workload", "checkout-api")
        )).isInstanceOf(ActionCatalogValidationException.class);

        assertThatThrownBy(() -> actionCatalogValidator.validateAndNormalize(
                "SCALE_K8S_DEPLOYMENT",
                Map.of("namespace", "payments", "deployment", "checkout-api", "replicas", 50)
        )).isInstanceOf(ActionCatalogValidationException.class);
    }
}
