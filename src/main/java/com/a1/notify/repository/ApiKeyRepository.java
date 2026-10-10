package com.a1.notify.repository;

import com.a1.notify.domain.ApiKeyEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {
    Optional<ApiKeyEntity> findByKeyHashAndActiveTrue(String keyHash);

    List<ApiKeyEntity> findByProjectId(Long projectId);
}
