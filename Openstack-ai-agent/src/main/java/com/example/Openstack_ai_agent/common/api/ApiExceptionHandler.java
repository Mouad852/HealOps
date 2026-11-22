package com.example.Openstack_ai_agent.common.api;

import com.example.Openstack_ai_agent.conversation.ConversationNotFoundException;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionNotAllowedException;
import com.example.Openstack_ai_agent.incident.IncidentNotFoundException;
import com.example.Openstack_ai_agent.incident.IncidentVersionConflictException;
import com.example.Openstack_ai_agent.incident.InvalidIncidentTransitionException;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidationException;
import com.example.Openstack_ai_agent.remediation.ApprovalConflictException;
import com.example.Openstack_ai_agent.remediation.PolicyDeniedException;
import com.example.Openstack_ai_agent.remediation.ProposedActionNotFoundException;
import com.example.Openstack_ai_agent.report.IncidentReportConflictException;
import com.example.Openstack_ai_agent.runbook.RunbookConflictException;
import com.example.Openstack_ai_agent.runbook.RunbookNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
        problem.setTitle("Access denied");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(PolicyDeniedException.class)
    public ResponseEntity<ProblemDetail> handlePolicyDenied(PolicyDeniedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Policy denied");
        problem.setProperty("policyVersion", exception.getDecision().policyVersion());
        problem.setProperty("ruleId", exception.getDecision().ruleId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleIncidentNotFound(IncidentNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Incident not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(ProposedActionNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProposedActionNotFound(ProposedActionNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Proposed action not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler({ConversationNotFoundException.class, RunbookNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleResourceNotFound(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Resource not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler({
            InvalidIncidentTransitionException.class,
            IncidentVersionConflictException.class,
            EvidenceCollectionNotAllowedException.class,
            ApprovalConflictException.class,
            IncidentReportConflictException.class,
            RunbookConflictException.class,
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class
    })
    public ResponseEntity<ProblemDetail> handleTransitionConflict(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Incident transition conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler({IllegalArgumentException.class, ActionCatalogValidationException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more request fields are invalid");
        problem.setTitle("Request validation failed");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
