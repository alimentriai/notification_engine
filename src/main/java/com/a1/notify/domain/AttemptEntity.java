package com.a1.notify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_attempts")
@Getter
@Setter
@NoArgsConstructor
public class AttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID notificationId;
    private int attemptNo;
    private String provider;
    private String outcome;
    private String errorCode;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
