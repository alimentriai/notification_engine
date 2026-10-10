package com.a1.notify.core;

import com.a1.notify.api.dto.CreateNotificationRequest;
import com.a1.notify.api.dto.NotificationView;
import com.a1.notify.api.dto.PageResponse;
import com.a1.notify.config.NotifyProperties;
import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.domain.NotificationStatus;
import com.a1.notify.domain.Priority;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.domain.TemplateEntity;
import com.a1.notify.queue.NotificationQueue;
import com.a1.notify.repository.NotificationRepository;
import com.a1.notify.repository.ProjectRepository;
import com.a1.notify.security.Hashing;
import com.a1.notify.security.RateLimiter;
import com.a1.notify.template.TemplateService;
import com.a1.notify.template.TemplateService.Rendered;
import com.a1.notify.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

/** Validates a request, applies preferences, pins templates, persists one row per channel and enqueues delivery. */
@Service
public class NotificationService {

    public record CreateResult(String requestId, List<NotificationView> notifications) {
    }

    private final NotificationRepository notifications;
    private final ProjectRepository projects;
    private final TemplateService templates;
    private final PreferenceService preferences;
    private final NotificationStore store;
    private final NotificationQueue queue;
    private final RateLimiter rateLimiter;
    private final NotifyProperties props;
    private final ObjectMapper mapper;

    public NotificationService(NotificationRepository notifications, ProjectRepository projects, TemplateService templates,
                               PreferenceService preferences, NotificationStore store, NotificationQueue queue,
                               RateLimiter rateLimiter, NotifyProperties props, ObjectMapper mapper) {
        this.notifications = notifications;
        this.projects = projects;
        this.templates = templates;
        this.preferences = preferences;
        this.store = store;
        this.queue = queue;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.mapper = mapper;
    }

    @Transactional
    public CreateResult create(Long projectId, CreateNotificationRequest req) {
        ProjectEntity project = projects.findById(projectId).filter(ProjectEntity::isActive)
                .orElseThrow(() -> ApiException.notFound("Project not found"));
        if (req.category() == Category.OTP) {
            throw ApiException.badRequest("USE_OTP_API", "OTP notifications must be sent through /otp/send");
        }
        List<Channel> channels = resolveChannels(req);
        String email = channels.contains(Channel.EMAIL) ? RecipientValidator.email(req.recipient().email()) : null;
        String phone = channels.contains(Channel.SMS) ? RecipientValidator.phone(req.recipient().phone()) : null;
        String ref = recipientRef(req, email, phone);
        String locale = req.locale() == null || req.locale().isBlank() ? "en" : req.locale();
        Map<String, String> variables = stringify(req.variables());
        String variablesJson = writeJson(variables);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (req.expiresAt() != null && req.expiresAt().isBefore(now)) {
            throw ApiException.badRequest("ALREADY_EXPIRED", "expiresAt is in the past");
        }
        rateLimiter.check(project, channels.size());

        // Validate every channel (template exists, all variables present) before persisting anything.
        Map<Channel, TemplateEntity> resolved = new LinkedHashMap<>();
        Map<Channel, Rendered> rendered = new LinkedHashMap<>();
        for (Channel channel : channels) {
            TemplateEntity template = templates.resolve(projectId, req.templateCode(), channel, locale);
            rendered.put(channel, templates.render(template, variables));
            resolved.put(channel, template);
        }

        String requestId = UUID.randomUUID().toString();
        List<NotificationView> views = new ArrayList<>();
        List<UUID> toEnqueue = new ArrayList<>();
        for (Channel channel : channels) {
            String idemKey = req.idempotencyKey() == null || req.idempotencyKey().isBlank()
                    ? null : req.idempotencyKey().trim() + "|" + channel;
            if (idemKey != null) {
                Optional<NotificationEntity> existing = notifications.findByProjectIdAndIdempotencyKey(projectId, idemKey);
                if (existing.isPresent()) {
                    views.add(NotificationView.of(existing.get()));
                    continue;
                }
            }
            TemplateEntity template = resolved.get(channel);
            NotificationEntity n = new NotificationEntity();
            n.setId(UUID.randomUUID());
            n.setProjectId(projectId);
            n.setRequestId(requestId);
            n.setRecipientRef(ref);
            n.setType(req.type());
            n.setCategory(req.category());
            n.setChannel(channel);
            n.setTemplateCode(req.templateCode());
            n.setLocale(template.getLocale());
            n.setTemplateVersion(template.getVersion());
            n.setPriority(req.priority() == null ? Priority.NORMAL : req.priority());
            n.setCorrelationId(MDC.get("correlationId"));
            n.setIdempotencyKey(idemKey);
            n.setAttempts(0);
            n.setRetryable(false);
            n.setExpiresAt(req.expiresAt());
            n.setCreatedAt(now);
            n.setUpdatedAt(now);

            boolean allowed = preferences.isAllowed(projectId, ref, channel, req.category());
            if (!allowed) {
                n.setStatus(NotificationStatus.CANCELLED);
                n.setLastErrorCode("PREFERENCE_OPT_OUT");
                notifications.save(n);
                store.recordEvent(n.getId(), null, NotificationStatus.CANCELLED, "PREFERENCE_OPT_OUT");
            } else if (channel == Channel.IN_APP) {
                Rendered r = rendered.get(channel);
                n.setTitle(r.subject() != null ? r.subject() : req.type());
                n.setBody(r.text());
                n.setStatus(NotificationStatus.DELIVERED);
                n.setSentAt(now);
                notifications.save(n);
                store.recordEvent(n.getId(), null, NotificationStatus.DELIVERED, null);
            } else {
                n.setStatus(NotificationStatus.PENDING);
                n.setRecipientEmail(email);
                n.setRecipientPhone(phone);
                n.setVariablesJson(variablesJson);
                n.setNextAttemptAt(req.scheduledAt() != null && req.scheduledAt().isAfter(now) ? req.scheduledAt() : now);
                notifications.save(n);
                store.recordEvent(n.getId(), null, NotificationStatus.PENDING, null);
                if (!n.getNextAttemptAt().isAfter(now)) {
                    toEnqueue.add(n.getId());
                }
            }
            views.add(NotificationView.of(n));
        }
        enqueueAfterCommit(toEnqueue);
        return new CreateResult(requestId, views);
    }

    private void enqueueAfterCommit(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ids.forEach(id -> queue.enqueue(id, 0));
                }
            });
        } else {
            ids.forEach(id -> queue.enqueue(id, 0));
        }
    }

    @Transactional(readOnly = true)
    public NotificationView get(Long projectId, UUID id) {
        return notifications.findByIdAndProjectId(id, projectId)
                .map(NotificationView::of)
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
    }

    public record Filter(NotificationStatus status, Channel channel, String type, Category category,
                         String recipientRef, OffsetDateTime from, OffsetDateTime to) {
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> search(Long projectId, Filter f, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Specification<NotificationEntity> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("projectId"), projectId));
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.channel() != null) {
                p.add(cb.equal(root.get("channel"), f.channel()));
            }
            if (f.type() != null && !f.type().isBlank()) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.category() != null) {
                p.add(cb.equal(root.get("category"), f.category()));
            }
            if (f.recipientRef() != null && !f.recipientRef().isBlank()) {
                p.add(cb.equal(root.get("recipientRef"), f.recipientRef()));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("createdAt"), f.to()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<NotificationEntity> result = notifications.findAll(spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(result.getContent().stream().map(NotificationView::of).toList(),
                result.getTotalElements(), safePage, safeSize);
    }

    @Transactional
    public NotificationView retry(Long projectId, UUID id) {
        NotificationEntity n = store.manualRetry(id, projectId);
        enqueueAfterCommit(List.of(n.getId()));
        return NotificationView.of(n);
    }

    private List<Channel> resolveChannels(CreateNotificationRequest req) {
        Set<Channel> channels = new LinkedHashSet<>();
        if (req.channels() != null) {
            channels.addAll(req.channels());
        }
        if (channels.isEmpty()) {
            for (String name : props.defaultChannelsFor(req.category().name())) {
                channels.add(Channel.valueOf(name.trim().toUpperCase()));
            }
        }
        if (channels.isEmpty()) {
            throw ApiException.badRequest("NO_CHANNELS", "No channels given and no defaults configured for " + req.category());
        }
        Map<String, Boolean> flags = props.channelFlags();
        for (Channel channel : channels) {
            if (!Boolean.TRUE.equals(flags.get(channel.name()))) {
                throw ApiException.badRequest("CHANNEL_DISABLED", "Channel " + channel + " is not enabled");
            }
        }
        return new ArrayList<>(channels);
    }

    private String recipientRef(CreateNotificationRequest req, String email, String phone) {
        String ref = req.recipient().ref();
        if (ref != null && !ref.isBlank()) {
            return ref.trim();
        }
        String address = email != null ? email : phone;
        if (address == null) {
            throw ApiException.badRequest("RECIPIENT_REQUIRED", "recipient.ref is required for in-app and push notifications");
        }
        return "addr:" + Hashing.sha256Hex(address.toLowerCase()).substring(0, 16);
    }

    private Map<String, String> stringify(Map<String, Object> variables) {
        Map<String, String> out = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, String.valueOf(v));
                }
            });
        }
        return out;
    }

    private String writeJson(Map<String, String> variables) {
        try {
            String json = mapper.writeValueAsString(variables);
            if (json.length() > 4000) {
                throw ApiException.badRequest("VARIABLES_TOO_LARGE", "variables exceed 4000 characters");
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("INVALID_VARIABLES", "variables could not be serialised");
        }
    }
}
