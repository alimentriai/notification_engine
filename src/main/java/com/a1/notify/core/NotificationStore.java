package com.a1.notify.core;

import com.a1.notify.domain.AttemptEntity;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.EventEntity;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.domain.NotificationStatus;
import com.a1.notify.provider.ProviderResult;
import com.a1.notify.repository.AttemptRepository;
import com.a1.notify.repository.EventRepository;
import com.a1.notify.repository.NotificationRepository;
import com.a1.notify.web.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions around provider calls, so no DB transaction is held while talking to AWS. */
@Service
public class NotificationStore {

    private final NotificationRepository notifications;
    private final AttemptRepository attempts;
    private final EventRepository events;
    private final RetryPolicy retryPolicy;

    public NotificationStore(NotificationRepository notifications, AttemptRepository attempts, EventRepository events,
                             RetryPolicy retryPolicy) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.events = events;
        this.retryPolicy = retryPolicy;
    }

    /** Result of finishing an attempt: the delay (seconds) before the next try, or null when no retry follows. */
    public record Completion(NotificationStatus status, Long retryDelaySeconds) {
    }

    @Transactional
    public Optional<NotificationEntity> claim(UUID id) {
        if (notifications.claim(id, now()) == 0) {
            return Optional.empty();
        }
        recordEvent(id, NotificationStatus.PENDING, NotificationStatus.PROCESSING, null);
        return notifications.findById(id);
    }

    @Transactional
    public Completion complete(UUID id, String providerName, ProviderResult result, OffsetDateTime startedAt) {
        NotificationEntity n = notifications.findById(id).orElseThrow();
        OffsetDateTime now = now();
        AttemptEntity attempt = new AttemptEntity();
        attempt.setNotificationId(id);
        attempt.setAttemptNo(n.getAttempts());
        attempt.setProvider(providerName);
        attempt.setOutcome(result.outcome().name());
        attempt.setErrorCode(result.errorCode());
        attempt.setStartedAt(startedAt);
        attempt.setFinishedAt(now);
        attempts.save(attempt);

        NotificationStatus from = n.getStatus();
        Long retryDelay = null;
        switch (result.outcome()) {
            case SENT -> {
                n.setStatus(NotificationStatus.SENT);
                n.setSentAt(now);
                n.setProviderMessageId(result.providerMessageId());
                n.setLastErrorCode(null);
                n.setRetryable(false);
                recordEvent(id, from, NotificationStatus.SENT, null);
            }
            case TRANSIENT_FAILURE -> {
                n.setLastErrorCode(result.errorCode());
                n.setRetryable(true);
                if (retryPolicy.canRetry(n.getAttempts())) {
                    retryDelay = retryPolicy.delaySeconds(n.getAttempts());
                    n.setStatus(NotificationStatus.PENDING);
                    n.setNextAttemptAt(now.plusSeconds(retryDelay));
                    recordEvent(id, from, NotificationStatus.PENDING, "RETRY_SCHEDULED");
                } else {
                    n.setStatus(NotificationStatus.FAILED);
                    recordEvent(id, from, NotificationStatus.FAILED, "RETRIES_EXHAUSTED");
                }
            }
            case PERMANENT_FAILURE -> {
                n.setStatus(NotificationStatus.FAILED);
                n.setLastErrorCode(result.errorCode());
                n.setRetryable(false);
                recordEvent(id, from, NotificationStatus.FAILED, result.errorCode());
            }
        }
        n.setProcessingStartedAt(null);
        n.setUpdatedAt(now);
        notifications.save(n);
        return new Completion(n.getStatus(), retryDelay);
    }

    /** Puts a stuck PROCESSING row back to PENDING (worker died mid-send), or fails it when attempts ran out. */
    @Transactional
    public void reap(UUID id) {
        NotificationEntity n = notifications.findById(id).orElse(null);
        if (n == null || n.getStatus() != NotificationStatus.PROCESSING) {
            return;
        }
        OffsetDateTime now = now();
        if (retryPolicy.canRetry(n.getAttempts())) {
            n.setStatus(NotificationStatus.PENDING);
            n.setNextAttemptAt(now);
            recordEvent(id, NotificationStatus.PROCESSING, NotificationStatus.PENDING, "PROCESSING_TIMEOUT");
        } else {
            n.setStatus(NotificationStatus.FAILED);
            n.setRetryable(true);
            n.setLastErrorCode("PROCESSING_TIMEOUT");
            recordEvent(id, NotificationStatus.PROCESSING, NotificationStatus.FAILED, "PROCESSING_TIMEOUT");
        }
        n.setProcessingStartedAt(null);
        n.setUpdatedAt(now);
        notifications.save(n);
    }

    /** Manual retry of a FAILED notification whose failure is retryable. */
    @Transactional
    public NotificationEntity manualRetry(UUID id, Long projectId) {
        NotificationEntity n = notifications.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
        if (n.getStatus() != NotificationStatus.FAILED || !n.isRetryable()) {
            throw ApiException.conflict("NOT_RETRYABLE", "Only retryable FAILED notifications can be retried");
        }
        if (n.getRecipientEmail() == null && n.getRecipientPhone() == null && n.getChannel() != Channel.PUSH) {
            throw ApiException.conflict("RECIPIENT_PURGED", "Recipient data was purged by the retention policy");
        }
        n.setStatus(NotificationStatus.PENDING);
        n.setAttempts(0);
        n.setNextAttemptAt(now());
        n.setUpdatedAt(now());
        recordEvent(id, NotificationStatus.FAILED, NotificationStatus.PENDING, "MANUAL_RETRY");
        return notifications.save(n);
    }

    /** After a DLQ redrive: make sure the row is eligible to be processed again. */
    @Transactional
    public void resetForRedrive(UUID id) {
        notifications.findById(id).ifPresent(n -> {
            if (n.getStatus() == NotificationStatus.PROCESSING || n.getStatus() == NotificationStatus.FAILED) {
                NotificationStatus from = n.getStatus();
                n.setStatus(NotificationStatus.PENDING);
                n.setNextAttemptAt(now());
                n.setUpdatedAt(now());
                recordEvent(id, from, NotificationStatus.PENDING, "DLQ_REDRIVE");
                notifications.save(n);
            }
        });
    }

    public void recordEvent(UUID id, NotificationStatus from, NotificationStatus to, String detail) {
        EventEntity event = new EventEntity();
        event.setNotificationId(id);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setDetailCode(detail);
        event.setCreatedAt(now());
        events.save(event);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
