package com.a1.notify.security;

import java.util.Set;

/** Authenticated caller: a project (via API key) or the platform admin (no project). */
public record ProjectPrincipal(Long projectId, String projectCode, boolean platformAdmin, Set<String> scopes) {

    public Long requireProjectId() {
        if (projectId == null) {
            throw new com.a1.notify.web.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "PROJECT_REQUIRED",
                    "This endpoint requires a project API key.");
        }
        return projectId;
    }
}
