export type IncidentSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export type IncidentStatus =
  | 'ALERTED'
  | 'GATHERING_EVIDENCE'
  | 'CORRELATING_SIGNALS'
  | 'DIAGNOSING'
  | 'AWAITING_APPROVAL'
  | 'EXECUTING'
  | 'VERIFYING'
  | 'CLOSED'
  | 'ROLLING_BACK'
  | 'ROLLED_BACK'
  | 'FAILED'
  | 'CANCELLED';

export const TERMINAL_INCIDENT_STATUSES: ReadonlySet<IncidentStatus> = new Set<IncidentStatus>([
  'CLOSED',
  'ROLLED_BACK',
  'FAILED',
  'CANCELLED',
]);

export type IncidentWorkflowEvent =
  | 'ALERT_RECEIVED'
  | 'COLLECTION_STARTED'
  | 'EVIDENCE_GATHERED'
  | 'SIGNALS_CORRELATED'
  | 'ACTION_PROPOSED'
  | 'NO_ACTION_REQUIRED'
  | 'ACTION_APPROVED'
  | 'ACTION_REJECTED'
  | 'EXECUTION_SUCCEEDED'
  | 'EXECUTION_FAILED'
  | 'VERIFICATION_PASSED'
  | 'VERIFICATION_FAILED'
  | 'ROLLBACK_SUCCEEDED'
  | 'ROLLBACK_FAILED'
  | 'PHASE_RETRIED';

export type EvidenceType =
  | 'ALERT_CONTEXT'
  | 'METRIC'
  | 'LOG'
  | 'KUBERNETES_EVENT'
  | 'OPENSTACK_EVENT'
  | 'TRACE'
  | 'COLLECTION_ERROR';

export type RedactionStatus = 'NOT_REQUIRED' | 'REDACTED';

export type HealthSnapshotPhase = 'BEFORE' | 'AFTER' | 'POST_ROLLBACK';

export type ActionType =
  | 'NO_ACTION'
  | 'RESTART_CONTAINER'
  | 'RESTART_K8S_WORKLOAD'
  | 'SCALE_K8S_DEPLOYMENT'
  | 'ROLLBACK_K8S_DEPLOYMENT'
  | 'RESTART_OPENSTACK_SERVICE';

export type ActionRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type ProposedActionStatus = 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | 'FAILED';

export type ApprovalDecisionType = 'APPROVED' | 'REJECTED';

export type ToolExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT';

export type ConversationMessageRole =
  | 'USER'
  | 'ASSISTANT'
  | 'SYSTEM'
  | 'TOOL_REQUEST'
  | 'TOOL_RESPONSE'
  | 'ERROR';

export type RunbookStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export type AllowedMetricName =
  | 'cpu_percent'
  | 'memory_percent'
  | 'error_rate'
  | 'latency_ms'
  | 'restarts';

export type DashboardEventType =
  | 'incident.updated'
  | 'tool-execution.updated'
  | 'approval.created'
  | 'approval.updated'
  | 'service-health.updated'
  | 'conversation.token'
  | 'conversation.completed';

export const DASHBOARD_EVENT_TYPES: readonly DashboardEventType[] = [
  'incident.updated',
  'tool-execution.updated',
  'approval.created',
  'approval.updated',
  'service-health.updated',
  'conversation.token',
  'conversation.completed',
] as const;

export interface CurrentUserResponse {
  readonly username: string;
  readonly authorities: readonly string[];
}

export interface EnvironmentResponse {
  readonly id: string;
  readonly name: string;
  readonly kind: string;
  readonly displayLabel: string;
  readonly enabled: boolean;
  readonly capabilities: Readonly<Record<string, unknown>>;
  readonly createdAt: string;
}

export interface CursorPageResponse<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | null;
}

export interface IncidentResponse {
  readonly id: string;
  readonly source: string;
  readonly externalAlertId: string | null;
  readonly fingerprint: string;
  readonly environmentId: string;
  readonly severity: IncidentSeverity;
  readonly service: string;
  readonly status: IncidentStatus;
  readonly startedAt: string;
  readonly createdAt: string;
  readonly version: number;
  readonly labels: Readonly<Record<string, string>>;
  readonly annotations: Readonly<Record<string, string>>;
  readonly traceId: string | null;
}

export interface CreateIncidentRequest {
  readonly source: string;
  readonly externalAlertId?: string;
  readonly fingerprint: string;
  readonly environmentId: string;
  readonly severity: IncidentSeverity;
  readonly service: string;
  readonly startedAt: string;
  readonly labels: Readonly<Record<string, string>>;
  readonly annotations: Readonly<Record<string, string>>;
  readonly traceId?: string;
}

export interface RetryIncidentRequest {
  readonly reason: string;
  readonly expectedVersion: number;
}

export interface IncidentEventResponse {
  readonly id: string;
  readonly sequence: number;
  readonly fromStatus: IncidentStatus | null;
  readonly toStatus: IncidentStatus;
  readonly eventType: IncidentWorkflowEvent;
  readonly actor: string;
  readonly reason: string | null;
  readonly traceId: string | null;
  readonly occurredAt: string;
  readonly metadata: Readonly<Record<string, string>>;
}

export interface EvidenceItemResponse {
  readonly id: string;
  readonly type: EvidenceType;
  readonly source: string;
  readonly target: string;
  readonly observedAt: string;
  readonly windowStartedAt: string;
  readonly windowEndedAt: string;
  readonly traceId: string | null;
  readonly excerpt: string;
  readonly contentHash: string;
  readonly redactionStatus: RedactionStatus;
  readonly metadata: Readonly<Record<string, string>>;
  readonly createdAt: string;
}

export interface DiagnosisHypothesis {
  readonly title: string;
  readonly description: string;
  readonly confidence: number;
  readonly evidenceIds: readonly string[];
  readonly ruleCode: string | null;
}

export interface DiagnosisResponse {
  readonly id: string;
  readonly incidentId: string;
  readonly summary: string;
  readonly hypotheses: readonly DiagnosisHypothesis[];
  readonly confidence: number;
  readonly citedEvidenceIds: readonly string[];
  readonly modelVersion: string;
  readonly ruleVersion: string;
  readonly createdAt: string;
}

export interface ProposedActionResponse {
  readonly id: string;
  readonly incidentId: string;
  readonly diagnosisId: string | null;
  readonly actionType: ActionType;
  readonly arguments: Readonly<Record<string, unknown>>;
  readonly riskLevel: ActionRiskLevel;
  readonly rollbackStrategy: string | null;
  readonly status: ProposedActionStatus;
  readonly version: number;
  readonly commandPreview: string;
  readonly createdAt: string;
}

export interface HealthSnapshotResponse {
  readonly id: string;
  readonly incidentId: string;
  readonly snapshotPhase: HealthSnapshotPhase;
  readonly signals: Readonly<Record<string, unknown>>;
  readonly capturedAt: string;
}

export interface HealthComparisonResponse {
  readonly incidentId: string;
  readonly before: HealthSnapshotResponse | null;
  readonly after: HealthSnapshotResponse | null;
  readonly postRollback: HealthSnapshotResponse | null;
  readonly snapshots: readonly HealthSnapshotResponse[];
}

export interface ApprovalDecisionResponse {
  readonly id: string;
  readonly actionId: string;
  readonly incidentId: string;
  readonly decision: ApprovalDecisionType;
  readonly actor: string;
  readonly reason: string | null;
  readonly decidedAt: string;
  readonly expectedVersion: number;
  readonly idempotencyKey: string;
  readonly actionStatus: ProposedActionStatus;
  readonly actionVersion: number;
  readonly policyVersion: string;
}

export interface ApprovalQueueItemResponse {
  readonly actionId: string;
  readonly incidentId: string;
  readonly diagnosisId: string | null;
  readonly environmentId: string;
  readonly service: string;
  readonly incidentStatus: IncidentStatus;
  readonly actionType: ActionType;
  readonly arguments: Readonly<Record<string, unknown>>;
  readonly riskLevel: ActionRiskLevel;
  readonly rollbackStrategy: string | null;
  readonly status: ProposedActionStatus;
  readonly version: number;
  readonly commandPreview: string;
  readonly createdAt: string;
  readonly latestDecision: ApprovalDecisionResponse | null;
}

export interface SubmitApprovalDecisionRequest {
  readonly decision: ApprovalDecisionType;
  readonly reason: string;
  readonly expectedVersion: number;
  readonly idempotencyKey: string;
}

export interface ToolExecutionResponse {
  readonly id: string;
  readonly actionId: string | null;
  readonly incidentId: string;
  readonly idempotencyKey: string;
  readonly attempt: number;
  readonly startedAt: string;
  readonly endedAt: string | null;
  readonly status: ToolExecutionStatus;
  readonly sanitizedInput: Readonly<Record<string, unknown>>;
  readonly sanitizedOutput: Readonly<Record<string, unknown>>;
  readonly traceId: string | null;
}

export interface IncidentReportResponse {
  readonly id: string;
  readonly incidentId: string;
  readonly finalOutcome: string;
  readonly generatedAt: string;
  readonly structuredReport: Readonly<Record<string, unknown>>;
  readonly renderedMarkdown: string;
}

export interface ActionTypeAnalytics {
  readonly actionType: ActionType;
  readonly totalOutcomes: number;
  readonly successfulOutcomes: number;
  readonly successRate: number;
  readonly rollbackCount: number;
  readonly rollbackRate: number;
  readonly meanDurationMs: number;
}

export interface ServiceAnalytics {
  readonly service: string;
  readonly environmentId: string;
  readonly totalOutcomes: number;
  readonly successfulOutcomes: number;
  readonly successRate: number;
  readonly rollbackCount: number;
  readonly rollbackRate: number;
  readonly meanDurationMs: number;
}

export interface RemediationAnalyticsResponse {
  readonly environmentId: string | null;
  readonly authorizedEnvironments: readonly string[];
  readonly totalOutcomes: number;
  readonly successfulOutcomes: number;
  readonly failedOutcomes: number;
  readonly overallSuccessRate: number;
  readonly rollbackCount: number;
  readonly rollbackRate: number;
  readonly rollbackSuccessRate: number | null;
  readonly meanApprovalDelayMs: number | null;
  readonly meanTimeToRecoveryMs: number | null;
  readonly byActionType: readonly ActionTypeAnalytics[];
  readonly byService: readonly ServiceAnalytics[];
}

export interface IncidentOverviewSummary {
  readonly totalCount: number;
  readonly activeCount: number;
  readonly byStatus: Readonly<Record<string, number>>;
  readonly bySeverity: Readonly<Record<string, number>>;
}

export interface ServiceHealthCard {
  readonly environmentId: string;
  readonly service: string;
  readonly healthStatus: string;
  readonly activeIncidentCount: number;
  readonly highestSeverity: string | null;
  readonly lastUpdatedAt: string;
}

export interface DashboardOverviewResponse {
  readonly environmentId: string | null;
  readonly environments: readonly EnvironmentResponse[];
  readonly incidents: IncidentOverviewSummary;
  readonly pendingApprovalCount: number;
  readonly serviceHealth: readonly ServiceHealthCard[];
  readonly remediationAnalytics: RemediationAnalyticsResponse;
  readonly generatedAt: string;
}

export interface DashboardEventEnvelope {
  readonly schemaVersion: string;
  readonly id: string;
  readonly sequenceNumber: number;
  readonly type: DashboardEventType;
  readonly occurredAt: string;
  readonly environmentId: string;
  readonly incidentId: string | null;
  readonly traceId: string | null;
  readonly entityVersion: number | null;
  readonly payload: Readonly<Record<string, unknown>>;
}

export interface MetricPoint {
  readonly timestamp: string;
  readonly value: number;
}

export interface MetricSeries {
  readonly label: string;
  readonly dimensions: Readonly<Record<string, string>>;
  readonly points: readonly MetricPoint[];
}

export interface MetricRangeResponse {
  readonly environmentId: string;
  readonly service: string;
  readonly metric: string;
  readonly unit: string;
  readonly from: string;
  readonly to: string;
  readonly requestedStepSeconds: number;
  readonly effectiveStepSeconds: number;
  readonly downsampled: boolean;
  readonly dataSource: string;
  readonly series: readonly MetricSeries[];
}

export interface LogRecordDto {
  readonly timestamp: string;
  readonly service: string;
  readonly level: string;
  readonly message: string;
  readonly traceId: string | null;
  readonly incidentId: string | null;
  readonly source: string;
  readonly redacted: boolean;
  readonly attributes: Readonly<Record<string, string>>;
}

export interface LogQueryResponse {
  readonly environmentId: string;
  readonly from: string;
  readonly to: string;
  readonly limit: number;
  readonly truncated: boolean;
  readonly dataSource: string;
  readonly records: readonly LogRecordDto[];
}

export interface TraceSummaryDto {
  readonly traceId: string;
  readonly rootService: string;
  readonly rootOperation: string;
  readonly startedAt: string;
  readonly durationMs: number;
  readonly spanCount: number;
  readonly errorSpanCount: number;
  readonly services: readonly string[];
  readonly incidentId: string | null;
}

export interface TraceListResponse {
  readonly environmentId: string;
  readonly from: string;
  readonly to: string;
  readonly limit: number;
  readonly dataSource: string;
  readonly traces: readonly TraceSummaryDto[];
}

export interface TraceSpanNode {
  readonly spanId: string;
  readonly parentSpanId: string | null;
  readonly service: string;
  readonly operation: string;
  readonly startedAt: string;
  readonly durationMs: number;
  readonly status: string;
  readonly attributes: Readonly<Record<string, string>>;
  readonly children: readonly TraceSpanNode[];
}

export interface TraceSpanSummary {
  readonly spanId: string;
  readonly parentSpanId: string | null;
  readonly service: string;
  readonly operation: string;
  readonly durationMs: number;
  readonly status: string;
}

export interface TraceDetailResponse {
  readonly traceId: string;
  readonly environmentId: string;
  readonly rootService: string;
  readonly rootOperation: string;
  readonly startedAt: string;
  readonly totalDurationMs: number;
  readonly spanCount: number;
  readonly errorSpanCount: number;
  readonly dataSource: string;
  readonly spanTree: readonly TraceSpanNode[];
  readonly criticalPath: readonly TraceSpanSummary[];
  readonly slowestSpans: readonly TraceSpanSummary[];
}

export interface TopologyNode {
  readonly id: string;
  readonly service: string;
  readonly environmentId: string;
  readonly healthStatus: string;
  readonly activeIncidentCount: number;
  readonly highestSeverity: string | null;
  readonly spanCount: number;
  readonly errorRatePercent: number;
}

export interface TopologyEdge {
  readonly sourceService: string;
  readonly targetService: string;
  readonly callCount: number;
  readonly errorCount: number;
  readonly avgLatencyMs: number;
}

export interface TopologyResponse {
  readonly environmentId: string;
  readonly from: string;
  readonly to: string;
  readonly dataSource: string;
  readonly nodes: readonly TopologyNode[];
  readonly edges: readonly TopologyEdge[];
}

export interface ConversationSessionResponse {
  readonly id: string;
  readonly environmentId: string;
  readonly title: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ConversationMessageResponse {
  readonly id: string;
  readonly sessionId: string;
  readonly role: ConversationMessageRole;
  readonly content: string;
  readonly evidenceId: string | null;
  readonly executionId: string | null;
  readonly incidentId: string | null;
  readonly createdAt: string;
}

export interface ConversationDetailResponse {
  readonly id: string;
  readonly environmentId: string;
  readonly title: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly messages: readonly ConversationMessageResponse[];
}

export interface CreateConversationRequest {
  readonly environmentId: string;
  readonly title?: string;
}

export interface PostConversationMessageRequest {
  readonly content: string;
  readonly incidentId?: string | null;
  readonly evidenceId?: string | null;
  readonly executionId?: string | null;
}

export interface PostConversationMessageResponse {
  readonly messageId: string;
  readonly sessionId: string;
  readonly role: ConversationMessageRole;
  readonly content: string;
  readonly evidenceId: string | null;
  readonly executionId: string | null;
  readonly incidentId: string | null;
  readonly createdAt: string;
  readonly status: string;
}

export interface RunbookStep {
  readonly order: number;
  readonly actionType: ActionType;
  readonly arguments: Readonly<Record<string, unknown>>;
  readonly description: string | null;
  readonly commandPreview: string;
  readonly riskLevel: ActionRiskLevel;
  readonly rollbackStrategy: string | null;
}

export interface RunbookStepRequest {
  readonly actionType: ActionType;
  readonly arguments: Readonly<Record<string, unknown>>;
  readonly description: string;
}

export interface RunbookVersionResponse {
  readonly id: string;
  readonly runbookId: string;
  readonly versionNumber: number;
  readonly typedSteps: readonly RunbookStep[];
  readonly policyRequirements: Readonly<Record<string, unknown>>;
  readonly publishedBy: string | null;
  readonly createdAt: string;
}

export interface RunbookDetailResponse {
  readonly id: string;
  readonly name: string;
  readonly status: RunbookStatus;
  readonly currentVersion: number;
  readonly matchCriteria: Readonly<Record<string, unknown>>;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly latestVersion: RunbookVersionResponse | null;
  readonly versions: readonly RunbookVersionResponse[];
}

export interface RunbookSuggestionResponse {
  readonly runbookId: string;
  readonly name: string;
  readonly versionNumber: number;
  readonly status: RunbookStatus;
  readonly matchScore: number;
  readonly matchedReasons: readonly string[];
  readonly historicalSuccessRate: number | null;
  readonly historicalOutcomeCount: number;
  readonly matchCriteria: Readonly<Record<string, unknown>>;
  readonly typedSteps: readonly RunbookStep[];
  readonly policyRequirements: Readonly<Record<string, unknown>>;
  readonly readOnlyProposal: boolean;
  readonly requiresPolicyAndApproval: boolean;
}

export interface DraftRunbookFromIncidentRequest {
  readonly name?: string;
  readonly policyRequirements?: Readonly<Record<string, unknown>>;
}

export interface UpdateRunbookRequest {
  readonly name?: string;
  readonly matchCriteria?: Readonly<Record<string, unknown>>;
  readonly typedSteps: readonly RunbookStepRequest[];
  readonly policyRequirements?: Readonly<Record<string, unknown>>;
}
