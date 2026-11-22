import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AllowedMetricName,
  ApprovalDecisionResponse,
  ApprovalQueueItemResponse,
  ConversationDetailResponse,
  ConversationSessionResponse,
  CreateConversationRequest,
  CreateIncidentRequest,
  CurrentUserResponse,
  CursorPageResponse,
  DashboardOverviewResponse,
  DiagnosisResponse,
  DraftRunbookFromIncidentRequest,
  EnvironmentResponse,
  EvidenceItemResponse,
  HealthComparisonResponse,
  IncidentEventResponse,
  IncidentReportResponse,
  IncidentResponse,
  IncidentSeverity,
  IncidentStatus,
  LogQueryResponse,
  MetricRangeResponse,
  PostConversationMessageRequest,
  PostConversationMessageResponse,
  ProposedActionResponse,
  RemediationAnalyticsResponse,
  RetryIncidentRequest,
  RunbookDetailResponse,
  RunbookStatus,
  RunbookSuggestionResponse,
  SubmitApprovalDecisionRequest,
  ToolExecutionResponse,
  TopologyResponse,
  TraceDetailResponse,
  TraceListResponse,
  UpdateRunbookRequest,
} from './api.models';

export interface IncidentPageQuery {
  readonly environmentId?: string;
  readonly status?: IncidentStatus | '';
  readonly severity?: IncidentSeverity | '';
  readonly cursor?: string | null;
  readonly limit?: number;
}

export interface MetricRangeQuery {
  readonly environmentId: string;
  readonly service: string;
  readonly metric: AllowedMetricName;
  readonly from?: string;
  readonly to?: string;
  readonly stepSeconds?: number;
}

export interface LogsQuery {
  readonly environmentId: string;
  readonly service?: string;
  readonly incidentId?: string;
  readonly traceId?: string;
  readonly from?: string;
  readonly to?: string;
  readonly limit?: number;
}

export interface TracesQuery {
  readonly environmentId: string;
  readonly service?: string;
  readonly from?: string;
  readonly to?: string;
  readonly limit?: number;
}

export interface TopologyQuery {
  readonly environmentId: string;
  readonly from?: string;
  readonly to?: string;
}

@Injectable({ providedIn: 'root' })
export class HealopsApiClient {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1';

  getCurrentUser(): Observable<CurrentUserResponse> {
    return this.http.get<CurrentUserResponse>(`${this.baseUrl}/auth/me`);
  }

  getEnvironments(): Observable<EnvironmentResponse[]> {
    return this.http.get<EnvironmentResponse[]>(`${this.baseUrl}/environments`);
  }

  getOverview(environmentId?: string): Observable<DashboardOverviewResponse> {
    return this.http.get<DashboardOverviewResponse>(`${this.baseUrl}/overview`, {
      params: this.buildParams({ environmentId }),
    });
  }

  getIncidentsPage(query: IncidentPageQuery): Observable<CursorPageResponse<IncidentResponse>> {
    return this.http.get<CursorPageResponse<IncidentResponse>>(`${this.baseUrl}/incidents/page`, {
      params: this.buildParams({
        environmentId: query.environmentId,
        status: query.status || undefined,
        severity: query.severity || undefined,
        cursor: query.cursor || undefined,
        limit: query.limit,
      }),
    });
  }

  getIncident(incidentId: string): Observable<IncidentResponse> {
    return this.http.get<IncidentResponse>(`${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}`);
  }

  createIncident(request: CreateIncidentRequest): Observable<IncidentResponse> {
    return this.http.post<IncidentResponse>(`${this.baseUrl}/incidents`, request);
  }

  retryIncident(incidentId: string, request: RetryIncidentRequest): Observable<IncidentResponse> {
    return this.http.post<IncidentResponse>(
      `${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}/retry`,
      request
    );
  }

  getIncidentTimeline(incidentId: string): Observable<IncidentEventResponse[]> {
    return this.http.get<IncidentEventResponse[]>(
      `${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}/timeline`
    );
  }

  getIncidentEvidence(incidentId: string): Observable<EvidenceItemResponse[]> {
    return this.http.get<EvidenceItemResponse[]>(
      `${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}/evidence`
    );
  }

  getIncidentDiagnoses(incidentId: string): Observable<DiagnosisResponse[]> {
    return this.http.get<DiagnosisResponse[]>(
      `${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}/diagnoses`
    );
  }

  getIncidentProposedActions(incidentId: string): Observable<ProposedActionResponse[]> {
    return this.http.get<ProposedActionResponse[]>(
      `${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}/proposed-actions`
    );
  }

  getIncidentHealthComparisons(incidentId: string): Observable<HealthComparisonResponse> {
    return this.http.get<HealthComparisonResponse>(
      `${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}/health-comparisons`
    );
  }

  getIncidentReport(incidentId: string): Observable<IncidentReportResponse> {
    return this.http.get<IncidentReportResponse>(
      `${this.baseUrl}/incidents/${encodeURIComponent(incidentId)}/report`
    );
  }

  getApprovals(
    environmentId?: string,
    status: 'PENDING' | 'ALL' | 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | 'FAILED' = 'PENDING'
  ): Observable<ApprovalQueueItemResponse[]> {
    return this.http.get<ApprovalQueueItemResponse[]>(`${this.baseUrl}/approvals`, {
      params: this.buildParams({ environmentId, status }),
    });
  }

  submitApprovalDecision(
    actionId: string,
    request: SubmitApprovalDecisionRequest
  ): Observable<ApprovalDecisionResponse> {
    const headers = new HttpHeaders({
      'Idempotency-Key': request.idempotencyKey,
    });
    return this.http.post<ApprovalDecisionResponse>(
      `${this.baseUrl}/approvals/${encodeURIComponent(actionId)}/decisions`,
      request,
      { headers }
    );
  }

  getToolExecutions(incidentId?: string): Observable<ToolExecutionResponse[]> {
    return this.http.get<ToolExecutionResponse[]>(`${this.baseUrl}/tool-executions`, {
      params: this.buildParams({ incidentId }),
    });
  }

  getMetricsRange(query: MetricRangeQuery): Observable<MetricRangeResponse> {
    return this.http.get<MetricRangeResponse>(`${this.baseUrl}/metrics/range`, {
      params: this.buildParams({
        environmentId: query.environmentId,
        service: query.service,
        metric: query.metric,
        from: query.from,
        to: query.to,
        stepSeconds: query.stepSeconds,
      }),
    });
  }

  getLogs(query: LogsQuery): Observable<LogQueryResponse> {
    return this.http.get<LogQueryResponse>(`${this.baseUrl}/logs`, {
      params: this.buildParams({
        environmentId: query.environmentId,
        service: query.service,
        incidentId: query.incidentId,
        traceId: query.traceId,
        from: query.from,
        to: query.to,
        limit: query.limit,
      }),
    });
  }

  getTraces(query: TracesQuery): Observable<TraceListResponse> {
    return this.http.get<TraceListResponse>(`${this.baseUrl}/traces`, {
      params: this.buildParams({
        environmentId: query.environmentId,
        service: query.service,
        from: query.from,
        to: query.to,
        limit: query.limit,
      }),
    });
  }

  getTrace(traceId: string, environmentId: string): Observable<TraceDetailResponse> {
    return this.http.get<TraceDetailResponse>(`${this.baseUrl}/traces/${encodeURIComponent(traceId)}`, {
      params: this.buildParams({ environmentId }),
    });
  }

  getTopology(query: TopologyQuery): Observable<TopologyResponse> {
    return this.http.get<TopologyResponse>(`${this.baseUrl}/topology`, {
      params: this.buildParams({
        environmentId: query.environmentId,
        from: query.from,
        to: query.to,
      }),
    });
  }

  listConversations(environmentId?: string): Observable<ConversationSessionResponse[]> {
    return this.http.get<ConversationSessionResponse[]>(`${this.baseUrl}/conversations`, {
      params: this.buildParams({ environmentId }),
    });
  }

  createConversation(request: CreateConversationRequest): Observable<ConversationSessionResponse> {
    return this.http.post<ConversationSessionResponse>(`${this.baseUrl}/conversations`, request);
  }

  getConversation(conversationId: string): Observable<ConversationDetailResponse> {
    return this.http.get<ConversationDetailResponse>(
      `${this.baseUrl}/conversations/${encodeURIComponent(conversationId)}`
    );
  }

  postConversationMessage(
    conversationId: string,
    request: PostConversationMessageRequest
  ): Observable<PostConversationMessageResponse> {
    return this.http.post<PostConversationMessageResponse>(
      `${this.baseUrl}/conversations/${encodeURIComponent(conversationId)}/messages`,
      request
    );
  }

  listRunbooks(status?: RunbookStatus | ''): Observable<RunbookDetailResponse[]> {
    return this.http.get<RunbookDetailResponse[]>(`${this.baseUrl}/runbooks`, {
      params: this.buildParams({ status: status || undefined }),
    });
  }

  getRunbook(runbookId: string): Observable<RunbookDetailResponse> {
    return this.http.get<RunbookDetailResponse>(
      `${this.baseUrl}/runbooks/${encodeURIComponent(runbookId)}`
    );
  }

  getRunbookSuggestions(incidentId: string): Observable<RunbookSuggestionResponse[]> {
    return this.http.get<RunbookSuggestionResponse[]>(`${this.baseUrl}/runbooks/suggestions`, {
      params: this.buildParams({ incidentId }),
    });
  }

  draftRunbookFromIncident(
    incidentId: string,
    request?: DraftRunbookFromIncidentRequest
  ): Observable<RunbookDetailResponse> {
    return this.http.post<RunbookDetailResponse>(
      `${this.baseUrl}/runbooks/draft-from-incident/${encodeURIComponent(incidentId)}`,
      request ?? {}
    );
  }

  updateRunbook(
    runbookId: string,
    request: UpdateRunbookRequest
  ): Observable<RunbookDetailResponse> {
    return this.http.put<RunbookDetailResponse>(
      `${this.baseUrl}/runbooks/${encodeURIComponent(runbookId)}`,
      request
    );
  }

  publishRunbook(runbookId: string): Observable<RunbookDetailResponse> {
    return this.http.post<RunbookDetailResponse>(
      `${this.baseUrl}/runbooks/${encodeURIComponent(runbookId)}/publish`,
      {}
    );
  }

  getRemediationAnalytics(environmentId?: string): Observable<RemediationAnalyticsResponse> {
    return this.http.get<RemediationAnalyticsResponse>(`${this.baseUrl}/analytics/remediation`, {
      params: this.buildParams({ environmentId }),
    });
  }

  private buildParams(entries: Record<string, string | number | boolean | undefined>): HttpParams {
    const clean: Record<string, string> = {};
    for (const [key, value] of Object.entries(entries)) {
      if (value !== undefined && value !== '') {
        clean[key] = String(value);
      }
    }
    return new HttpParams({ fromObject: clean });
  }
}
