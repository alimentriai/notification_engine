package com.a1.notify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class NotificationEntity {

    @Id
    private UUID id;
    private Long projectId;
    private String requestId;
    private String recipientRef;
    private String recipientEmail;
    private String recipientPhone;
    private String type;
    @Enumerated(EnumType.STRING)
    private Category category;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private String templateCode;
    private String locale;
    private Integer templateVersion;
    @Enumerated(EnumType.STRING)
    private Priority priority;
    @Enumerated(EnumType.STRING)
    private NotificationStatus status;
    private String variablesJson;
    private String title;
    private String body;
    private String correlationId;
    private String idempotencyKey;
    private int attempts;
    private OffsetDateTime nextAttemptAt;
    private OffsetDateTime processingStartedAt;
    private String lastErrorCode;
    private boolean retryable;
    private String providerMessageId;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime sentAt;
    private OffsetDateTime readAt;
}
