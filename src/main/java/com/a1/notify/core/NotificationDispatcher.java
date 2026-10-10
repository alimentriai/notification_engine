package com.a1.notify.core;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.DeviceTokenEntity;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.domain.TemplateEntity;
import com.a1.notify.provider.ChannelSender;
import com.a1.notify.provider.ProviderResult;
import com.a1.notify.queue.NotificationQueue;
import com.a1.notify.repository.DeviceTokenRepository;
import com.a1.notify.repository.ProjectRepository;
import com.a1.notify.template.TemplateService;
import com.a1.notify.template.TemplateService.Rendered;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Sends one notification. Safe to call more than once for the same id: only the worker that wins the
 * atomic PENDING->PROCESSING claim performs the send, so duplicate queue deliveries cannot double-send.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationStore store;
    private final ProjectRepository projects;
    private final TemplateService templates;
    private final ChannelSender sender;
    private final DeviceTokenRepository deviceTokens;
    private final NotificationQueue queue;
    private final NotifyProperties props;
    private final ObjectMapper mapper;

    public NotificationDispatcher(NotificationStore store, ProjectRepository projects, TemplateService templates,
                                  ChannelSender sender, DeviceTokenRepository deviceTokens, NotificationQueue queue,
                                  NotifyProperties props, ObjectMapper mapper) {
        this.store = store;
        this.projects = projects;
        this.templates = templates;
        this.sender = sender;
        this.deviceTokens = deviceTokens;
        this.queue = queue;
        this.props = props;
        this.mapper = mapper;
    }

    public void dispatch(UUID id) {
        Optional<NotificationEntity> claimed = store.claim(id);
        if (claimed.isEmpty()) {
            return;
        }
        NotificationEntity n = claimed.get();
        String previous = MDC.get("correlationId");
        if (n.getCorrelationId() != null) {
            MDC.put("correlationId", n.getCorrelationId());
        }
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
        ProviderResult result;
        try {
            result = send(n);
        } catch (RuntimeException ex) {
            log.warn("dispatch_error notificationId={} type={}", id, ex.getClass().getSimpleName());
            result = ProviderResult.transientFailure("DISPATCH_ERROR");
        }
        NotificationStore.Completion done = store.complete(id, providerName(n.getChannel()), result, started);
        log.info("notification_dispatched notificationId={} channel={} status={}", id, n.getChannel(), done.status());
        restore(previous);
        if (done.retryDelaySeconds() != null) {
            queue.enqueue(id, done.retryDelaySeconds());
        }
    }

    private ProviderResult send(NotificationEntity n) {
        if (!channelEnabled(n.getChannel())) {
            return ProviderResult.permanentFailure("CHANNEL_DISABLED");
        }
        if (n.getExpiresAt() != null && n.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            return ProviderResult.permanentFailure("EXPIRED");
        }
        ProjectEntity project = projects.findById(n.getProjectId()).orElse(null);
        if (project == null || !project.isActive()) {
            return ProviderResult.permanentFailure("PROJECT_INACTIVE");
        }
        Optional<TemplateEntity> template = templates.pinned(n.getProjectId(), n.getTemplateCode(), n.getChannel(),
                n.getLocale(), n.getTemplateVersion() == null ? 0 : n.getTemplateVersion());
        if (template.isEmpty()) {
            return ProviderResult.permanentFailure("TEMPLATE_NOT_FOUND");
        }
        Rendered rendered;
        try {
            rendered = templates.render(template.get(), variables(n));
        } catch (RuntimeException ex) {
            return ProviderResult.permanentFailure("TEMPLATE_RENDER_FAILED");
        }
        return switch (n.getChannel()) {
            case EMAIL -> n.getRecipientEmail() == null
                    ? ProviderResult.permanentFailure("NO_RECIPIENT")
                    : sender.sendEmail(project, n.getRecipientEmail(), rendered, n.getCorrelationId());
            case SMS -> n.getRecipientPhone() == null
                    ? ProviderResult.permanentFailure("NO_RECIPIENT")
                    : sender.sendSms(project, n.getRecipientPhone(), rendered, null, n.getCorrelationId());
            case PUSH -> sendPush(n, rendered);
            case IN_APP -> ProviderResult.permanentFailure("IN_APP_NOT_DISPATCHED");
        };
    }

    private ProviderResult sendPush(NotificationEntity n, Rendered rendered) {
        List<DeviceTokenEntity> tokens = deviceTokens.findByProjectIdAndUserRefAndActiveTrue(n.getProjectId(), n.getRecipientRef());
        if (tokens.isEmpty()) {
            return ProviderResult.permanentFailure("NO_DEVICE_TOKEN");
        }
        ProviderResult last = ProviderResult.permanentFailure("PUSH_FAILED");
        for (DeviceTokenEntity token : tokens) {
            ProviderResult result = sender.sendPush(token.getPlatform(), token.getToken(), rendered.subject(),
                    rendered.text(), n.getCorrelationId());
            if (result.isSent()) {
                return result;
            }
            last = result;
        }
        return last;
    }

    private boolean channelEnabled(Channel channel) {
        return Boolean.TRUE.equals(props.channelFlags().get(channel.name()));
    }

    private String providerName(Channel channel) {
        return switch (channel) {
            case EMAIL -> sender.emailProviderName();
            case SMS -> sender.smsProviderName();
            case PUSH -> sender.pushProviderName();
            case IN_APP -> "in-app";
        };
    }

    private Map<String, String> variables(NotificationEntity n) {
        if (n.getVariablesJson() == null || n.getVariablesJson().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(n.getVariablesJson(), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("variables unreadable");
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            MDC.remove("correlationId");
        } else {
            MDC.put("correlationId", previous);
        }
    }
}
