package com.example.Openstack_ai_agent.remediation;

import com.example.Openstack_ai_agent.incident.IncidentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tool-executions")
public class ToolExecutionController {
    private final ToolExecutionRepository toolExecutionRepository;
    private final IncidentService incidentService;

    public ToolExecutionController(
            ToolExecutionRepository toolExecutionRepository,
            IncidentService incidentService
    ) {
        this.toolExecutionRepository = toolExecutionRepository;
        this.incidentService = incidentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('incident:read')")
    @Transactional(readOnly = true)
    public List<ToolExecutionResponse> findToolExecutions(
            @RequestParam(required = false) UUID incidentId
    ) {
        if (incidentId != null) {
            incidentService.findById(incidentId);
            return toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId).stream()
                    .map(ToolExecutionResponse::from)
                    .toList();
        }
        return toolExecutionRepository.findAllByOrderByStartedAtDesc().stream()
                .map(ToolExecutionResponse::from)
                .toList();
    }
}
