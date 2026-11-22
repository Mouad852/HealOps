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
}
