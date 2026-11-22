package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/events")
public class DashboardEventController {
    private static final Set<String> FORBIDDEN_TOKEN_PARAMS = Set.of(
            "token", "access_token", "bearer", "authorization", "jwt"
    );

    private final DashboardEventPublisher dashboardEventPublisher;
    private final DashboardSseRegistry sseRegistry;

    public DashboardEventController(
            DashboardEventPublisher dashboardEventPublisher,
            DashboardSseRegistry sseRegistry
    ) {
        this.dashboardEventPublisher = dashboardEventPublisher;
        this.sseRegistry = sseRegistry;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('incident:read')")
    public SseEmitter streamEvents(
            @RequestParam String environmentId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        rejectQueryTokens(request);
        String authorizedEnv = EnvironmentAuthorizationSupport.requireAuthorizedEnvironment(environmentId, authentication);
        Long parsedLastEventId = parseLastEventId(lastEventIdHeader);

        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");

        return sseRegistry.registerAndReplay(
                authorizedEnv,
                parsedLastEventId,
                () -> dashboardEventPublisher.findEventsAfter(authorizedEnv, parsedLastEventId == null ? 0L : parsedLastEventId)
        );
    }

    private void rejectQueryTokens(HttpServletRequest request) {
        if (request == null || request.getParameterMap() == null) {
            return;
        }
        for (String paramName : request.getParameterMap().keySet()) {
            if (paramName != null && FORBIDDEN_TOKEN_PARAMS.contains(paramName.toLowerCase())) {
                throw new IllegalArgumentException(
                        "Authentication tokens are not permitted in SSE query parameters; use session cookies or request headers"
                );
            }
        }
    }

    static Long parseLastEventId(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return null;
        }
        String trimmed = rawHeader.trim();
        try {
            long value = Long.parseLong(trimmed);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Last-Event-ID header: must be a non-negative integer");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid Last-Event-ID header: must be a non-negative integer");
        }
    }
}
