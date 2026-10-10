package com.a1.notify.repository;

import com.a1.notify.domain.EventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface EventRepository extends JpaRepository<EventEntity, Long> {
    List<EventEntity> findByNotificationIdOrderById(UUID notificationId);

    @Modifying
    void deleteByNotificationIdIn(List<UUID> ids);
}
