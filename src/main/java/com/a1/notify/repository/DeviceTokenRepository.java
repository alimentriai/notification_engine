package com.a1.notify.repository;

import com.a1.notify.domain.DeviceTokenEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceTokenEntity, Long> {
    List<DeviceTokenEntity> findByProjectIdAndUserRefAndActiveTrue(Long projectId, String userRef);

    Optional<DeviceTokenEntity> findByProjectIdAndToken(Long projectId, String token);
}
