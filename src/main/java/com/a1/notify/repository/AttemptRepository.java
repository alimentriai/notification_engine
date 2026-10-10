package com.a1.notify.repository;

import com.a1.notify.domain.AttemptEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface AttemptRepository extends JpaRepository<AttemptEntity, Long> {
    List<AttemptEntity> findByNotificationIdOrderByAttemptNo(UUID notificationId);

    @Modifying
    void deleteByNotificationIdIn(List<UUID> ids);
}
