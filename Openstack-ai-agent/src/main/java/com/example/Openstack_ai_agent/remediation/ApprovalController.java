package com.example.Openstack_ai_agent.remediation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('incident:read')")
    public List<ApprovalQueueItemResponse> listApprovals(
            @RequestParam(required = false) String environmentId,
            @RequestParam(required = false) String status,
            Authentication authentication
    ) {
        return approvalService.listApprovals(environmentId, status, authentication);
    }

    @PostMapping("/{actionId}/decisions")
    @PreAuthorize("hasAuthority('incident:approve')")
    public ResponseEntity<ApprovalDecisionResponse> submitDecision(
            @PathVariable UUID actionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitApprovalDecisionRequest request,
            Authentication authentication
    ) {
        ApprovalService.DecisionSubmissionResult result =
                approvalService.submitDecision(actionId, idempotencyKey, request, authentication);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }
}
