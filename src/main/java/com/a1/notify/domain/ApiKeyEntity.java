package com.a1.notify.domain;

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
@Table(name = "project_api_keys")
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private String keyHash;
    private String keyPrefix;
    private String label;
    /** Comma separated scopes, e.g. SEND,READ. */
    private String scopes;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastUsedAt;
}
