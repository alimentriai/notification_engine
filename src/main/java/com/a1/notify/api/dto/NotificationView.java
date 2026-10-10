package com.a1.notify.api.dto;

import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.domain.NotificationStatus;
import com.a1.notify.domain.Priority;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Public representation: never includes recipient contact data, template variables or provider payloads. */
public record NotificationView(
        UUID id,
        String requestId,
        String recipientRef,
        String type,
        Category category,
        Channel channel,
        String templateCode,
        NotificationStatus status,
        Priority priority,
        int attempts,
        String lastErrorCode,
        boolean retryable,
        String correlationId,
        String title,
        String body,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime sentAt,
        OffsetDateTime readAt) {

    public static NotificationView of(NotificationEntity n) {
        return new NotificationView(n.getId(), n.getRequestId(), n.getRecipientRef(), n.getType(), n.getCategory(),
                n.getChannel(), n.getTemplateCode(), n.getStatus(), n.getPriority(), n.getAttempts(),
                n.getLastErrorCode(), n.isRetryable(), n.getCorrelationId(), n.getTitle(), n.getBody(),
                n.getCreatedAt(), n.getUpdatedAt(), n.getSentAt(), n.getReadAt());
    }
}
