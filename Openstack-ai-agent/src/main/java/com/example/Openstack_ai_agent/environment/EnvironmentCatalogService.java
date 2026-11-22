package com.example.Openstack_ai_agent.environment;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EnvironmentCatalogService {
    private final EnvironmentRepository environmentRepository;

    public EnvironmentCatalogService(EnvironmentRepository environmentRepository) {
        this.environmentRepository = environmentRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaultEnvironments() {
        ensureEnvironment(
                "local",
                "local",
                "LOCAL_DEV",
                "Local Development",
                true,
                capabilities(true, true, true, true, true, true, true)
        );
        ensureEnvironment(
                "staging",
                "staging",
                "STAGING",
                "Staging Infrastructure",
                true,
                capabilities(false, true, true, true, true, true, true)
        );
    }

    @Transactional(readOnly = true)
    public List<EnvironmentResponse> listAuthorizedEnvironments(Authentication authentication) {
        Set<String> authorizedEnvs = EnvironmentAuthorizationSupport.extractAuthorizedEnvironments(authentication);
        if (authorizedEnvs.isEmpty()) {
            return List.of();
        }
        return environmentRepository.findByEnabledTrueOrderByNameAsc().stream()
                .filter(env -> authorizedEnvs.contains(env.getId()))
                .map(EnvironmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EnvironmentResponse> listAuthorizedEnvironmentsForScope(Set<String> scopedEnvironments) {
        if (scopedEnvironments == null || scopedEnvironments.isEmpty()) {
            return List.of();
        }
        return environmentRepository.findByEnabledTrueOrderByNameAsc().stream()
                .filter(env -> scopedEnvironments.contains(env.getId()))
                .map(EnvironmentResponse::from)
                .toList();
    }

    private void ensureEnvironment(
            String id,
            String name,
            String kind,
            String displayLabel,
            boolean enabled,
            Map<String, Object> capabilities
    ) {
        if (!environmentRepository.existsById(id)) {
            environmentRepository.save(EnvironmentEntity.create(
                    id,
                    name,
                    kind,
                    displayLabel,
                    enabled,
                    capabilities
            ));
        }
    }

    private Map<String, Object> capabilities(
            boolean docker,
            boolean kubernetes,
            boolean openstack,
            boolean prometheus,
            boolean loki,
            boolean jaeger,
            boolean sseLiveUpdates
    ) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("docker", docker);
        flags.put("kubernetes", kubernetes);
        flags.put("openstack", openstack);
        flags.put("prometheus", prometheus);
        flags.put("loki", loki);
        flags.put("jaeger", jaeger);
        flags.put("sseLiveUpdates", sseLiveUpdates);
        flags.put("remediationApprovalRequired", true);
        return flags;
    }
}
