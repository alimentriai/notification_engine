package com.a1.notify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_templates")
@Getter
@Setter
@NoArgsConstructor
public class TemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private String code;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private String locale;
    private int version;
    private String subject;
    private String bodyHtml;
    private String bodyText;
    private String dltTemplateId;
    private boolean active;
    private OffsetDateTime createdAt;
}
