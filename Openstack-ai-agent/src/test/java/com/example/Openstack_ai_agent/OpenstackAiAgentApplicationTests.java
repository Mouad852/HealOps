package com.example.Openstack_ai_agent;

import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenstackAiAgentApplicationTests {

	private static final String INCIDENT_REQUEST = """
			{
			  "source": "manual-demo",
			  "externalAlertId": "alert-123",
			  "fingerprint": "checkout-api-high-error-rate",
			  "environmentId": "local",
			  "severity": "HIGH",
			  "service": "checkout-api",
			  "startedAt": "2025-01-01T00:00:00Z",
			  "labels": { "team": "payments" },
			  "annotations": { "summary": "Error rate is elevated" },
			  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
			}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DashboardEventRepository dashboardEventRepository;

	@Test
	void contextLoads() {
	}

	@Test
	@WithMockUser(username = "viewer", authorities = {"ROLE_VIEWER", "incident:read"})
	void openApiDocsEndpointLoadsWithoutError() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").exists())
				.andExpect(jsonPath("$.paths['/api/v1/incidents']").exists());
	}

	@Test
	void unauthenticatedRequestsToApiAreRejectedWhileHealthIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/incidents"))
				.andExpect(status().isUnauthorized());

		UUID randomId = UUID.randomUUID();
		mockMvc.perform(get("/api/v1/incidents/{incidentId}/diagnoses", randomId))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/incidents/{incidentId}/proposed-actions", randomId))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/incidents/{incidentId}/health-comparisons", randomId))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/approvals"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/tool-executions"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@WithMockUser(username = "viewer", authorities = {"ROLE_VIEWER", "incident:read"})
	void viewerCanReadIncidentsAndAuthMeButCannotMutate() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("viewer"))
				.andExpect(jsonPath("$.authorities[0]").value("ROLE_VIEWER"));

		mockMvc.perform(get("/api/v1/incidents"))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(INCIDENT_REQUEST.replace("checkout-api-high-error-rate", "viewer-forbidden-test")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.title").value("Access denied"));
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void createsAndReadsAnIncidentAndRecordsDashboardEvent() throws Exception {
		String request = INCIDENT_REQUEST.replace("checkout-api-high-error-rate", "checkout-api-read-test");
		MvcResult result = mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ALERTED"))
				.andExpect(jsonPath("$.severity").value("HIGH"))
				.andReturn();

		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		String incidentId = response.get("id").asText();

		assertThat(dashboardEventRepository.findByIncidentIdOrderBySequenceNumberAsc(UUID.fromString(incidentId)))
				.hasSize(1)
				.first()
				.satisfies(event -> {
					assertThat(event.getEventType()).isEqualTo("incident.updated");
					assertThat(event.getEnvironmentId()).isEqualTo("local");
				});

		mockMvc.perform(get("/api/v1/incidents/{incidentId}", incidentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incidentId))
				.andExpect(jsonPath("$.service").value("checkout-api"));

		mockMvc.perform(get("/api/v1/incidents/{incidentId}/timeline", incidentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].sequence").value(1))
				.andExpect(jsonPath("$[0].eventType").value("ALERT_RECEIVED"))
				.andExpect(jsonPath("$[0].toStatus").value("ALERTED"));

		mockMvc.perform(get("/api/v1/incidents")
				.param("environmentId", "local")
				.param("status", "ALERTED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(incidentId));
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void returnsTheExistingIncidentForTheSameDeduplicationKey() throws Exception {
		String request = INCIDENT_REQUEST.replace("checkout-api-high-error-rate", "checkout-api-deduplication-test");
		mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk());
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void ingestsAlertmanagerWebhookAndFiltersResolvedAlerts() throws Exception {
		String payload = """
				{
				  "status": "firing",
				  "receiver": "healops",
				  "alerts": [
				    {
				      "status": "firing",
				      "fingerprint": "am-fp-001",
				      "startsAt": "2025-01-01T00:00:00Z",
				      "labels": {
				        "alertname": "HighLatency",
				        "service": "orders-api",
				        "environment": "staging",
				        "severity": "critical"
				      },
				      "annotations": {
				        "summary": "p99 latency above 2s"
				      }
				    },
				    {
				      "status": "resolved",
				      "fingerprint": "am-fp-resolved",
				      "startsAt": "2025-01-01T00:00:00Z",
				      "labels": { "alertname": "OldAlert", "service": "old-api" }
				    }
				  ]
				}
				""";

		mockMvc.perform(post("/api/v1/incidents/webhooks/alertmanager")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].source").value("alertmanager"))
				.andExpect(jsonPath("$[0].environmentId").value("staging"))
				.andExpect(jsonPath("$[0].severity").value("CRITICAL"))
				.andExpect(jsonPath("$[0].service").value("orders-api"));
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void appliesLegalTransitionsDerivesAuthenticatedActorAndAppendsTimelineEvents() throws Exception {
		String request = INCIDENT_REQUEST.replace("checkout-api-high-error-rate", "checkout-api-transition-test");
		MvcResult created = mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(0))
				.andReturn();

		String incidentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		String transition = """
				{
				  "event": "COLLECTION_STARTED",
				  "actor": "spoofed-browser-actor",
				  "reason": "Beginning evidence collection",
				  "metadata": { "collector": "pending" },
				  "expectedVersion": 0
				}
				""";

		mockMvc.perform(post("/api/v1/incidents/{incidentId}/transitions", incidentId)
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(transition))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("GATHERING_EVIDENCE"))
				.andExpect(jsonPath("$.version").value(1));

		mockMvc.perform(get("/api/v1/incidents/{incidentId}/timeline", incidentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[1].sequence").value(2))
				.andExpect(jsonPath("$[1].eventType").value("COLLECTION_STARTED"))
				.andExpect(jsonPath("$[1].fromStatus").value("ALERTED"))
				.andExpect(jsonPath("$[1].toStatus").value("GATHERING_EVIDENCE"))
				.andExpect(jsonPath("$[1].actor").value("operator"));

		mockMvc.perform(post("/api/v1/incidents/{incidentId}/transitions", incidentId)
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(transition))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Incident transition conflict"));
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void rejectsIllegalTransitions() throws Exception {
		String request = INCIDENT_REQUEST.replace("checkout-api-high-error-rate", "checkout-api-illegal-transition-test");
		MvcResult created = mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andReturn();

		String incidentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/incidents/{incidentId}/transitions", incidentId)
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "event": "EVIDENCE_GATHERED",
						  "actor": "operator",
						  "expectedVersion": 0
						}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Incident transition conflict"));
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void rejectsRestrictedApprovalExecutionAndRollbackManualTransitionsWith403() throws Exception {
		String request = INCIDENT_REQUEST.replace("checkout-api-high-error-rate", "checkout-api-restricted-transition-test");
		MvcResult created = mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andReturn();

		String incidentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		for (String payload : new String[] {
				"""
				{"event":"ACTION_APPROVED","actor":"operator","expectedVersion":0}
				""",
				"""
				{"event":"EXECUTION_SUCCEEDED","actor":"operator","expectedVersion":0}
				""",
				"""
				{"event":"EXECUTION_FAILED","failureDisposition":"ROLLING_BACK","actor":"operator","expectedVersion":0}
				""",
				"""
				{"event":"ROLLBACK_SUCCEEDED","actor":"operator","expectedVersion":0}
				"""
		}) {
			mockMvc.perform(post("/api/v1/incidents/{incidentId}/transitions", incidentId)
					.with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content(payload))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.title").value("Access denied"));
		}
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void collectsAndPersistsBoundedAlertContextEvidence() throws Exception {
		String request = INCIDENT_REQUEST.replace("checkout-api-high-error-rate", "checkout-api-evidence-test");
		MvcResult created = mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andReturn();

		String incidentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/incidents/{incidentId}/transitions", incidentId)
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "event": "COLLECTION_STARTED",
						  "actor": "workflow-engine",
						  "expectedVersion": 0
						}
						"""))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/incidents/{incidentId}/evidence/collect", incidentId)
				.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].type").value("ALERT_CONTEXT"))
				.andExpect(jsonPath("$[0].source").value("manual-demo"))
				.andExpect(jsonPath("$[0].contentHash").isString());

		mockMvc.perform(get("/api/v1/incidents/{incidentId}/evidence", incidentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].type").value("ALERT_CONTEXT"))
				.andExpect(jsonPath("$[0].redactionStatus").value("NOT_REQUIRED"));
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void recordsAnAdapterFailureWithoutDiscardingOtherEvidence() throws Exception {
		String request = INCIDENT_REQUEST
				.replace("checkout-api-high-error-rate", "checkout-api-adapter-failure-test")
				.replace("\"team\": \"payments\"", "\"team\": \"payments\", \"collect-prometheus\": \"true\"");
		MvcResult created = mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andReturn();

		String incidentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/incidents/{incidentId}/transitions", incidentId)
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "event": "COLLECTION_STARTED",
						  "actor": "workflow-engine",
						  "expectedVersion": 0
						}
						"""))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/incidents/{incidentId}/evidence/collect", incidentId)
				.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].type").value("ALERT_CONTEXT"))
				.andExpect(jsonPath("$[1].type").value("COLLECTION_ERROR"))
				.andExpect(jsonPath("$[1].metadata.collector").value("PrometheusEvidenceCollector"));
	}

	@Test
	@WithMockUser(username = "operator", authorities = {"ROLE_OPERATOR", "incident:read", "incident:write"})
	void rejectsInvalidIncidentRequestsAsProblemDetails() throws Exception {
		mockMvc.perform(post("/api/v1/incidents")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"source\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Request validation failed"))
				.andExpect(jsonPath("$.errors.source").exists());
	}
}
