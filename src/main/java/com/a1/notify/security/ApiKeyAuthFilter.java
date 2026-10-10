package com.a1.notify.security;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.domain.ApiKeyEntity;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.repository.ApiKeyRepository;
import com.a1.notify.repository.ProjectRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates service-to-service calls via the X-Api-Key header (keys are stored only as SHA-256 hashes). */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final Set<String> PROJECT_SCOPES = Set.of("SEND", "READ", "RETRY", "TEMPLATES", "PREFERENCES", "OTP", "INBOX");

    private final ApiKeyRepository keys;
    private final ProjectRepository projects;
    private final String bootstrapHash;

    public ApiKeyAuthFilter(ApiKeyRepository keys, ProjectRepository projects, NotifyProperties props) {
        this.keys = keys;
        this.projects = projects;
        String bootstrap = props.getBootstrapAdminKey();
        this.bootstrapHash = bootstrap == null || bootstrap.isBlank() ? null : Hashing.sha256Hex(bootstrap.trim());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw != null && !raw.isBlank()) {
            authenticate(raw.trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String raw) {
        String hash = Hashing.sha256Hex(raw);
        if (bootstrapHash != null && Hashing.constantTimeEquals(bootstrapHash, hash)) {
            set(new ProjectPrincipal(null, null, true, Set.of("ADMIN")), List.of(new SimpleGrantedAuthority("ADMIN")));
            return;
        }
        Optional<ApiKeyEntity> key = keys.findByKeyHashAndActiveTrue(hash);
        if (key.isEmpty()) {
            return;
        }
        Optional<ProjectEntity> project = projects.findById(key.get().getProjectId());
        if (project.isEmpty() || !project.get().isActive()) {
            return;
        }
        Set<String> scopes = Arrays.stream(key.get().getScopes().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(HashSet::new));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (key.get().getLastUsedAt() == null || key.get().getLastUsedAt().isBefore(now.minusMinutes(5))) {
            key.get().setLastUsedAt(now);
            keys.save(key.get());
        }
        set(new ProjectPrincipal(project.get().getId(), project.get().getCode(), false, scopes),
                scopes.stream().map(s -> (GrantedAuthority) new SimpleGrantedAuthority(s)).toList());
    }

    private void set(ProjectPrincipal principal, List<GrantedAuthority> authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}
