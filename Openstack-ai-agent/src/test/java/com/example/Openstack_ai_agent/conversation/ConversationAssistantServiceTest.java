package com.example.Openstack_ai_agent.conversation;

import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.LlmDiagnosisService;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.remediation.ProposedActionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationAssistantServiceTest {

    @Test
    void hydratesChatMemoryFromPersistedHistoryWithoutDuplicatingCurrentTriggerMessage() {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.defaultSystem(org.mockito.ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.defaultAdvisors(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.client.advisor.api.Advisor.class)))
                .thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        CopilotToolQueryService toolQueryService = mock(CopilotToolQueryService.class);
        ConversationSanitizer sanitizer = new ConversationSanitizer();

        // Even with llmEnabled=true, placeholder GEMINI_API_KEY triggers graceful deterministic fallback
        ConversationAssistantService assistantService = new ConversationAssistantService(
                builder,
                chatMemory,
                sanitizer,
                toolQueryService,
                true,
                "replace_with_your_gemini_key"
        );

        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        ConversationSessionResponse session = new ConversationSessionResponse(
                sessionId, "demo", "Demo Copilot", now.minusMinutes(5), now
        );
        ConversationMessageResponse pastUser = new ConversationMessageResponse(
                UUID.randomUUID(), sessionId, ConversationMessageRole.USER,
                "First question token=secret-1", null, null, null, now.minusMinutes(3)
        );
        ConversationMessageResponse pastAssistant = new ConversationMessageResponse(
                UUID.randomUUID(), sessionId, ConversationMessageRole.ASSISTANT,
                "First answer", null, null, null, now.minusMinutes(2)
        );
        ConversationMessageResponse currentUser = new ConversationMessageResponse(
                UUID.randomUUID(), sessionId, ConversationMessageRole.USER,
                "Follow-up question", null, null, null, now
        );

        var context = new ConversationPersistenceService.ConversationTurnContext(
                session,
                currentUser,
                List.of(pastUser, pastAssistant, currentUser),
                null
        );

        List<ConversationPersistenceService.GeneratedMessage> generated = assistantService.processTurn(context);

        assertThat(generated).hasSize(1);
        assertThat(generated.getFirst().content()).contains("turns=2").contains("Follow-up question");

        List<Message> memory = chatMemory.get(sessionId.toString());
        assertThat(memory).hasSize(4);
        assertThat(memory.get(0)).isInstanceOf(UserMessage.class);
        assertThat(memory.get(0).getText()).contains("token=[REDACTED]").doesNotContain("secret-1");
        assertThat(memory.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(memory.get(2)).isInstanceOf(UserMessage.class);
        assertThat(memory.get(2).getText()).isEqualTo("Follow-up question");
        assertThat(memory.get(3)).isInstanceOf(AssistantMessage.class);

        verifyNoInteractions(toolQueryService);
    }

    @Test
    void llmDiagnosisServiceRejectsPlaceholderApiKeys() {
        assertThat(LlmDiagnosisService.isUsableApiKey("")).isFalse();
        assertThat(LlmDiagnosisService.isUsableApiKey("test-key")).isFalse();
        assertThat(LlmDiagnosisService.isUsableApiKey("replace_with_your_gemini_key")).isFalse();
        assertThat(LlmDiagnosisService.isUsableApiKey("AIzaSyDummyValidLookingKey123456")).isTrue();
    }

    @Test
    void copilotToolQueryServiceEnforcesEnvironmentIsolationAndBounds() {
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        var diagnosisRepo = mock(com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository.class);
        var evidenceRepo = mock(com.example.Openstack_ai_agent.evidence.EvidenceItemRepository.class);
        var actionRepo = mock(com.example.Openstack_ai_agent.remediation.ProposedActionRepository.class);
        var snapshotRepo = mock(com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository.class);

        IncidentEntity demoIncident = IncidentEntity.alertReceived(new CreateIncidentRequest(
                "manual-demo", "ext-1", "fp-1", "demo", IncidentSeverity.HIGH,
                "demo-workload", OffsetDateTime.now(), Map.of(), Map.of("summary", "token=secret-demo high latency"), "trace-1"
        ));
        IncidentEntity stagingIncident = IncidentEntity.alertReceived(new CreateIncidentRequest(
                "alertmanager", "ext-2", "fp-2", "staging", IncidentSeverity.CRITICAL,
                "billing-api", OffsetDateTime.now(), Map.of(), Map.of("summary", "staging outage"), "trace-2"
        ));

        when(incidentRepo.findByEnvironmentId("demo")).thenReturn(List.of(demoIncident));
        when(incidentRepo.findById(stagingIncident.getId())).thenReturn(java.util.Optional.of(stagingIncident));
        when(actionRepo.countByIncidentIdInAndStatus(java.util.Set.of(demoIncident.getId()), ProposedActionStatus.PROPOSED))
                .thenReturn(1L);
        when(snapshotRepo.findByIncidentIdIn(java.util.Set.of(demoIncident.getId())))
                .thenReturn(List.of(HealthSnapshotEntity.of(
                        demoIncident.getId(),
                        HealthSnapshotPhase.BEFORE,
                        Map.of("healthStatus", "DEGRADED", "telemetryStatus", "COMPLETE", "latencyMs", 1250.0)
                )));

        CopilotToolQueryService tools = new CopilotToolQueryService(
                incidentRepo,
                diagnosisRepo,
                evidenceRepo,
                actionRepo,
                snapshotRepo,
                new com.example.Openstack_ai_agent.remediation.ActionCatalogValidator(),
                new ConversationSanitizer()
        );
        CopilotToolQueryService.ScopedCopilotTools scoped = tools.scopedTools("demo");

        List<Map<String, Object>> active = scoped.listActiveIncidents();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().get("service")).isEqualTo("demo-workload");
        assertThat(String.valueOf(active.getFirst().get("summary"))).contains("[REDACTED]").doesNotContain("secret-demo");

        Map<String, Object> overview = scoped.getEnvironmentHealthOverview();
        assertThat(overview.get("environmentId")).isEqualTo("demo");
        assertThat(overview.get("totalIncidents")).isEqualTo(1);
        assertThat(overview.get("activeIncidents")).isEqualTo(1L);
        assertThat(overview.get("pendingApprovals")).isEqualTo(1L);

        Map<String, Object> crossEnvInspect = scoped.inspectIncidentSummary(stagingIncident.getId().toString());
        assertThat(crossEnvInspect).containsKey("error");
        assertThat(String.valueOf(crossEnvInspect.get("error"))).contains("not found in environment demo");

        verify(incidentRepo, never()).findAll();
        verify(snapshotRepo, never()).findAll();
        verify(actionRepo, never()).findAll();
    }

    @Test
    void copilotLogAndTraceToolsQueryLiveLokiJaegerWithRedactionAndFallBackToLocalEvidence() throws Exception {
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        var diagnosisRepo = mock(com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository.class);
        var evidenceRepo = mock(com.example.Openstack_ai_agent.evidence.EvidenceItemRepository.class);
        var actionRepo = mock(com.example.Openstack_ai_agent.remediation.ProposedActionRepository.class);
        var snapshotRepo = mock(com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository.class);
        var backendClient = mock(com.example.Openstack_ai_agent.observability.ObservabilityBackendClient.class);
        var validator = new com.example.Openstack_ai_agent.observability.ObservabilityQueryValidator(
                new com.example.Openstack_ai_agent.observability.ObservabilityQueryProperties()
        );
        var calculator = new com.example.Openstack_ai_agent.observability.TraceAnalysisCalculator();
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        CopilotToolQueryService tools = new CopilotToolQueryService(
                incidentRepo,
                diagnosisRepo,
                evidenceRepo,
                actionRepo,
                snapshotRepo,
                new com.example.Openstack_ai_agent.remediation.ActionCatalogValidator(),
                new ConversationSanitizer(),
                backendClient,
                validator,
                calculator
        );
        CopilotToolQueryService.ScopedCopilotTools scoped = tools.scopedTools("demo");

        // 1. Live Loki + Jaeger mode
        when(backendClient.isLiveQueryEnabled()).thenReturn(true);
        var lokiJson = objectMapper.readTree("""
                {
                  "status": "success",
                  "data": {
                    "result": [
                      {
                        "stream": { "service_name": "demo-workload", "level": "ERROR" },
                        "values": [ [ "4000000000000000000", "Downstream failure token=loki-secret-1" ] ]
                      }
                    ]
                  }
                }
                """);
        when(backendClient.queryLokiRange(
                org.mockito.ArgumentMatchers.contains("deployment_environment=\"demo\""),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(java.util.Optional.of(lokiJson));

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        var jaegerJson = objectMapper.readTree("""
                {
                  "result": {
                    "resourceSpans": [
                      {
                        "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "demo-workload" } } ] },
                        "scopeSpans": [ { "spans": [ {
                          "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                          "spanId": "s1",
                          "name": "GET /work",
                          "startTimeUnixNano": "1700000000000000000",
                          "endTimeUnixNano":   "1700000001200000000",
                          "status": { "code": 2 }
                        } ] } ]
                      },
                      {
                        "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "demo-inventory-db" } } ] },
                        "scopeSpans": [ { "spans": [ {
                          "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                          "spanId": "s2",
                          "parentSpanId": "s1",
                          "name": "SELECT inventory_items",
                          "startTimeUnixNano": "1700000000100000000",
                          "endTimeUnixNano":   "1700000001050000000",
                          "attributes": [ { "key": "error.message", "value": { "stringValue": "db timeout secret=jaeger-secret-2" } } ],
                          "status": { "code": 2 }
                        } ] } ]
                      }
                    ]
                  }
                }
                """);
        when(backendClient.queryJaegerV3TraceById(traceId)).thenReturn(java.util.Optional.of(jaegerJson));

        Map<String, Object> liveLogs = scoped.searchServiceLogs("demo-workload", null, 30, 10);
        assertThat(liveLogs.get("dataSource")).isEqualTo("LOKI");
        assertThat(liveLogs.get("environmentId")).isEqualTo("demo");
        assertThat(liveLogs.get("logs").toString()).contains("token=[REDACTED]").doesNotContain("loki-secret-1");

        Map<String, Object> liveTrace = scoped.inspectServiceTraces("demo-workload", traceId, 30, 5);
        assertThat(liveTrace.get("dataSource")).isEqualTo("JAEGER_V3");
        assertThat(liveTrace.get("errorSpanCount")).isEqualTo(2);
        assertThat(liveTrace.get("criticalPath").toString()).contains("demo-inventory-db/SELECT inventory_items");
        assertThat(liveTrace.get("errorSpans").toString()).contains("secret=[REDACTED]").doesNotContain("jaeger-secret-2");

        // 2. Invalid input rejected safely
        assertThat(scoped.searchServiceLogs("bad service!@#", null, 30, 10)).containsKey("error");
        assertThat(scoped.inspectServiceTraces(null, "invalid trace!", 30, 5)).containsKey("error");

        // 3. Offline / live-query-disabled fallback to environment-scoped local evidence
        when(backendClient.isLiveQueryEnabled()).thenReturn(false);
        IncidentEntity demoIncident = IncidentEntity.alertReceived(new CreateIncidentRequest(
                "alertmanager", "ext-log-1", "fp-log-1", "demo", IncidentSeverity.HIGH,
                "demo-workload", OffsetDateTime.now().minusMinutes(5), Map.of(), Map.of(), traceId
        ));
        when(incidentRepo.findByEnvironmentIdAndService("demo", "demo-workload")).thenReturn(List.of(demoIncident));
        when(incidentRepo.findByEnvironmentIdAndTraceId("demo", traceId)).thenReturn(List.of(demoIncident));

        var logEv = mock(com.example.Openstack_ai_agent.evidence.EvidenceItemEntity.class);
        when(logEv.getId()).thenReturn(UUID.randomUUID());
        when(logEv.getIncidentId()).thenReturn(demoIncident.getId());
        when(logEv.getObservedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        when(logEv.getSource()).thenReturn("loki");
        when(logEv.getTarget()).thenReturn("demo-workload");
        when(logEv.getTraceId()).thenReturn(traceId);
        when(logEv.getExcerpt()).thenReturn("ERROR local stored log password=local-pw");

        var traceEv = mock(com.example.Openstack_ai_agent.evidence.EvidenceItemEntity.class);
        when(traceEv.getId()).thenReturn(UUID.randomUUID());
        when(traceEv.getIncidentId()).thenReturn(demoIncident.getId());
        when(traceEv.getObservedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        when(traceEv.getSource()).thenReturn("jaeger");
        when(traceEv.getTarget()).thenReturn("demo-workload");
        when(traceEv.getTraceId()).thenReturn(traceId);
        when(traceEv.getExcerpt()).thenReturn("{\"traceId\":\"" + traceId + "\",\"errorSpanCount\":2}");

        when(evidenceRepo.findByIncidentIdInAndTypeOrderByObservedAtDesc(
                java.util.Set.of(demoIncident.getId()),
                com.example.Openstack_ai_agent.evidence.EvidenceType.LOG
        )).thenReturn(List.of(logEv));
        when(evidenceRepo.findByIncidentIdInAndTypeOrderByObservedAtDesc(
                java.util.Set.of(demoIncident.getId()),
                com.example.Openstack_ai_agent.evidence.EvidenceType.TRACE
        )).thenReturn(List.of(traceEv));

        Map<String, Object> fallbackLogs = scoped.searchServiceLogs("demo-workload", traceId, 30, 10);
        assertThat(fallbackLogs.get("dataSource")).isEqualTo("LOCAL_EVIDENCE");
        assertThat(fallbackLogs.get("logs").toString()).contains("password=[REDACTED]").doesNotContain("local-pw");

        Map<String, Object> fallbackTraces = scoped.inspectServiceTraces("demo-workload", traceId, 30, 5);
        assertThat(fallbackTraces.get("dataSource")).isEqualTo("LOCAL_EVIDENCE");
        assertThat(fallbackTraces.get("traceCount")).isEqualTo(1);

        // Ensure cross-env repositories were never queried globally
        verify(incidentRepo, never()).findAll();
        verify(evidenceRepo, never()).findAll();
    }

    @Test
    void copilotListsLiveKubernetesPodsAndBuildsOperationalContext() throws Exception {
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        var diagnosisRepo = mock(com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository.class);
        var evidenceRepo = mock(com.example.Openstack_ai_agent.evidence.EvidenceItemRepository.class);
        var actionRepo = mock(com.example.Openstack_ai_agent.remediation.ProposedActionRepository.class);
        var snapshotRepo = mock(com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository.class);
        var adapterClient = mock(com.example.Openstack_ai_agent.evidence.EvidenceAdapterClient.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        when(incidentRepo.findByEnvironmentId("local")).thenReturn(List.of());
        when(adapterClient.kubernetesPods("pitstop")).thenReturn(objectMapper.readTree("""
                [
                  {
                    "name": "customer-5dd65b5bf-58btl",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "1/1",
                    "restarts": 0,
                    "terminating": false,
                    "node": "desktop-control-plane",
                    "containers": ["customer"]
                  },
                  {
                    "name": "gateway-9b894c5d-2fcpm",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "1/1",
                    "restarts": 0,
                    "terminating": false,
                    "node": "desktop-control-plane",
                    "containers": ["gateway"]
                  }
                ]
                """));

        CopilotToolQueryService tools = new CopilotToolQueryService(
                incidentRepo,
                diagnosisRepo,
                evidenceRepo,
                actionRepo,
                snapshotRepo,
                new com.example.Openstack_ai_agent.remediation.ActionCatalogValidator(),
                new ConversationSanitizer()
        );
        tools.setEvidenceAdapterClient(adapterClient);

        Map<String, Object> podsResult = tools.scopedTools("local").listKubernetesPods("pitstop");
        assertThat(podsResult.get("podCount")).isEqualTo(2);
        assertThat(podsResult.get("pods").toString())
                .contains("customer-5dd65b5bf-58btl")
                .contains("gateway-9b894c5d-2fcpm");

        String liveContext = tools.buildLiveOperationalContext("local", "how many pods are running", null);
        assertThat(liveContext)
                .contains("Live Kubernetes Pods")
                .contains("customer-5dd65b5bf-58btl")
                .contains("gateway-9b894c5d-2fcpm");
    }

    @Test
    void copilotQueuesSingleAndMultiCommandProposalsIntoApprovals() {
        IncidentRepository incidentRepo = mock(IncidentRepository.class);
        var eventRepo = mock(com.example.Openstack_ai_agent.incident.IncidentEventRepository.class);
        var evidenceRepo = mock(com.example.Openstack_ai_agent.evidence.EvidenceItemRepository.class);
        var diagnosisRepo = mock(com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository.class);
        var snapshotRepo = mock(com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository.class);
        var actionRepo = mock(com.example.Openstack_ai_agent.remediation.ProposedActionRepository.class);
        var publisher = mock(com.example.Openstack_ai_agent.observability.DashboardEventPublisher.class);
        var txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);

        when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(incidentRepo.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepo.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotRepo.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(diagnosisRepo.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(actionRepo.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        CopilotActionProposalService proposalService = new CopilotActionProposalService(
                incidentRepo,
                eventRepo,
                evidenceRepo,
                diagnosisRepo,
                snapshotRepo,
                actionRepo,
                new com.example.Openstack_ai_agent.remediation.ActionCatalogValidator(),
                publisher,
                new ConversationSanitizer(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                txManager
        );

        String llmReplyWithTwoCommands = """
                I have prepared the requested scaling and restart commands for your cluster.
                <PROPOSED_ACTIONS_JSON>
                [
                  {
                    "actionType": "SCALE_K8S_DEPLOYMENT",
                    "service": "gateway",
                    "reason": "Scale gateway to 2 replicas",
                    "arguments": { "namespace": "pitstop", "deployment": "gateway", "replicas": 2 }
                  },
                  {
                    "actionType": "RESTART_K8S_WORKLOAD",
                    "service": "customer",
                    "reason": "Restart customer deployment",
                    "arguments": { "namespace": "pitstop", "workload": "customer", "kind": "Deployment" }
                  }
                ]
                </PROPOSED_ACTIONS_JSON>
                """;

        var parsed = proposalService.extractAndQueueProposals(
                UUID.randomUUID(),
                "local",
                "scale gateway to 2 replicas and restart customer",
                llmReplyWithTwoCommands
        );

        assertThat(parsed.queuedActions()).hasSize(2);
        assertThat(parsed.queuedActions().get(0).commandPreview())
                .isEqualTo("kubectl scale deployment/gateway --replicas=2 -n pitstop");
        assertThat(parsed.queuedActions().get(1).commandPreview())
                .isEqualTo("kubectl rollout restart deployment/customer -n pitstop");
        assertThat(parsed.formattedReply())
                .doesNotContain("<PROPOSED_ACTIONS_JSON>")
                .contains("Queued for Operator Approval (`/approvals`)")
                .contains("kubectl scale deployment/gateway --replicas=2 -n pitstop")
                .contains("kubectl rollout restart deployment/customer -n pitstop");
    }
}
