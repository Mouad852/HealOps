package com.example.Openstack_ai_agent.common.api;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        String nextCursor
) {
    public CursorPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
