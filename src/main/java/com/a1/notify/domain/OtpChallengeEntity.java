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
@Table(name = "otp_challenges")
@Getter
@Setter
@NoArgsConstructor
public class OtpChallengeEntity {

    @Id
    private UUID id;
    private Long projectId;
    private String recipientHash;
    private String purpose;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private String salt;
    private String codeHash;
    private OffsetDateTime expiresAt;
    private int attempts;
    private int maxAttempts;
    private OffsetDateTime createdAt;
    private OffsetDateTime consumedAt;
}
