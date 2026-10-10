package com.a1.notify.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentProject {

    private CurrentProject() {
    }

    public static ProjectPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ProjectPrincipal principal)) {
            throw new com.a1.notify.web.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Authentication required");
        }
        return principal;
    }
}
