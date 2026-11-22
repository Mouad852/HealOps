package com.example.Openstack_ai_agent.environment;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentController {
    private final EnvironmentCatalogService environmentCatalogService;

    public EnvironmentController(EnvironmentCatalogService environmentCatalogService) {
        this.environmentCatalogService = environmentCatalogService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('incident:read')")
    public List<EnvironmentResponse> listEnvironments(Authentication authentication) {
        return environmentCatalogService.listAuthorizedEnvironments(authentication);
    }
}
