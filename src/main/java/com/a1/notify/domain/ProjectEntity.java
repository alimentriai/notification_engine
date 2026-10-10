package com.a1.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String status;
    private String defaultSenderEmail;
    private String sesConfigSet;
    private String smsSenderId;
    private String smsEntityId;
    private int rateLimitPerMin;
    private int dailyQuota;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
