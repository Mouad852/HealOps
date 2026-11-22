package com.example.Openstack_ai_agent.common.api;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class EnvironmentAuthorizationSupport {
    public static final String ENV_AUTHORITY_PREFIX = "env:";

    private EnvironmentAuthorizationSupport() {
    }

    public static Set<String> extractAuthorizedEnvironments(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(auth -> auth != null && auth.startsWith(ENV_AUTHORITY_PREFIX) && auth.length() > ENV_AUTHORITY_PREFIX.length())
                .map(auth -> auth.substring(ENV_AUTHORITY_PREFIX.length()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static String requireAuthorizedEnvironment(String environmentId, Authentication authentication) {
        if (environmentId == null || environmentId.isBlank()) {
            throw new IllegalArgumentException("environmentId is required");
        }
        String normalized = environmentId.trim();
        Set<String> authorized = extractAuthorizedEnvironments(authentication);
        if (!authorized.contains(normalized)) {
            throw new AccessDeniedException("Caller is not authorized for environment '" + normalized + "'");
        }
        return normalized;
    }

    public static Set<String> resolveAuthorizedScope(String optionalEnvironmentId, Authentication authentication) {
        Set<String> authorized = extractAuthorizedEnvironments(authentication);
        if (optionalEnvironmentId != null && !optionalEnvironmentId.isBlank()) {
            String normalized = optionalEnvironmentId.trim();
            if (!authorized.contains(normalized)) {
                throw new AccessDeniedException("Caller is not authorized for environment '" + normalized + "'");
            }
            return Set.of(normalized);
        }
        return Set.copyOf(authorized);
    }
}
