package com.a1.notify.core;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.domain.ApiKeyEntity;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.repository.ApiKeyRepository;
import com.a1.notify.repository.ProjectRepository;
import com.a1.notify.security.ApiKeyAuthFilter;
import com.a1.notify.security.Hashing;
import com.a1.notify.web.ApiException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-admin operations: onboard a project and issue / revoke its API keys. */
@Service
public class ProjectAdminService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record IssuedKey(Long id, String key, String prefix, String label, Set<String> scopes) {
    }

    public record KeyView(Long id, String prefix, String label, Set<String> scopes, boolean active,
                          OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }

    private final ProjectRepository projects;
    private final ApiKeyRepository keys;
    private final NotifyProperties props;

    public ProjectAdminService(ProjectRepository projects, ApiKeyRepository keys, NotifyProperties props) {
        this.projects = projects;
        this.keys = keys;
        this.props = props;
    }

    @Transactional
    public ProjectEntity createProject(String code, String name, String senderEmail, String sesConfigSet,
                                       String smsSenderId, String smsEntityId, Integer ratePerMin, Integer dailyQuota) {
        if (projects.findByCode(code).isPresent()) {
            throw ApiException.conflict("PROJECT_EXISTS", "Project code already exists");
        }
        ProjectEntity p = new ProjectEntity();
        p.setCode(code);
        p.setName(name);
        p.setStatus("ACTIVE");
        p.setDefaultSenderEmail(senderEmail);
        p.setSesConfigSet(sesConfigSet);
        p.setSmsSenderId(smsSenderId);
        p.setSmsEntityId(smsEntityId);
        p.setRateLimitPerMin(ratePerMin == null ? props.getRateLimit().getDefaultPerMinute() : ratePerMin);
        p.setDailyQuota(dailyQuota == null ? props.getRateLimit().getDefaultDailyQuota() : dailyQuota);
        p.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return projects.save(p);
    }

    @Transactional(readOnly = true)
    public List<ProjectEntity> listProjects() {
        return projects.findAll();
    }

    /** The raw key is returned once and never stored; only its SHA-256 hash is kept. */
    @Transactional
    public IssuedKey issueKey(String projectCode, String label, Set<String> scopes) {
        ProjectEntity project = projects.findByCode(projectCode).orElseThrow(() -> ApiException.notFound("Project not found"));
        Set<String> requested = scopes == null || scopes.isEmpty() ? Set.of("SEND", "READ") : scopes;
        for (String scope : requested) {
            if (!ApiKeyAuthFilter.PROJECT_SCOPES.contains(scope)) {
                throw ApiException.badRequest("INVALID_SCOPE", "Unknown scope: " + scope);
            }
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String raw = "ntf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        ApiKeyEntity key = new ApiKeyEntity();
        key.setProjectId(project.getId());
        key.setKeyHash(Hashing.sha256Hex(raw));
        key.setKeyPrefix(raw.substring(0, 8));
        key.setLabel(label);
        key.setScopes(requested.stream().sorted().collect(Collectors.joining(",")));
        key.setActive(true);
        key.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        keys.save(key);
        return new IssuedKey(key.getId(), raw, key.getKeyPrefix(), label, requested);
    }

    /** Metadata only. The raw key cannot be recovered (only its hash is stored); issue a new key if it was lost. */
    @Transactional(readOnly = true)
    public List<KeyView> listKeys(String projectCode) {
        ProjectEntity project = projects.findByCode(projectCode).orElseThrow(() -> ApiException.notFound("Project not found"));
        return keys.findByProjectId(project.getId()).stream()
                .map(k -> new KeyView(k.getId(), k.getKeyPrefix(), k.getLabel(),
                        java.util.Arrays.stream(k.getScopes().split(",")).collect(Collectors.toCollection(java.util.TreeSet::new)),
                        k.isActive(), k.getCreatedAt(), k.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public void revokeKey(String projectCode, Long keyId) {
        ProjectEntity project = projects.findByCode(projectCode).orElseThrow(() -> ApiException.notFound("Project not found"));
        ApiKeyEntity key = keys.findById(keyId).filter(k -> k.getProjectId().equals(project.getId()))
                .orElseThrow(() -> ApiException.notFound("Key not found"));
        key.setActive(false);
    }

    @Transactional
    public void setStatus(String projectCode, boolean active) {
        projects.findByCode(projectCode).orElseThrow(() -> ApiException.notFound("Project not found"))
                .setStatus(active ? "ACTIVE" : "SUSPENDED");
    }
}
