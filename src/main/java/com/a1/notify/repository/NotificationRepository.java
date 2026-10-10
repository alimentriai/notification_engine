package com.a1.notify.repository;

import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.domain.NotificationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository
        extends JpaRepository<NotificationEntity, UUID>, JpaSpecificationExecutor<NotificationEntity> {

    Optional<NotificationEntity> findByIdAndProjectId(UUID id, Long projectId);

    Optional<NotificationEntity> findByProjectIdAndIdempotencyKey(Long projectId, String idempotencyKey);

    long countByProjectIdAndCreatedAtAfter(Long projectId, OffsetDateTime since);

    List<NotificationEntity> findByProjectIdAndRequestId(Long projectId, String requestId);

    /** Atomic claim: only one worker can move a row from PENDING to PROCESSING. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationEntity n set n.status = com.a1.notify.domain.NotificationStatus.PROCESSING, "
            + "n.processingStartedAt = :now, n.updatedAt = :now, n.attempts = n.attempts + 1 "
            + "where n.id = :id and n.status = com.a1.notify.domain.NotificationStatus.PENDING")
    int claim(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Query("select n.id from NotificationEntity n where n.status = com.a1.notify.domain.NotificationStatus.PENDING "
            + "and n.nextAttemptAt <= :now and n.channel <> com.a1.notify.domain.Channel.IN_APP order by n.nextAttemptAt")
    List<UUID> findDueIds(@Param("now") OffsetDateTime now, Pageable pageable);

    @Query("select n from NotificationEntity n where n.status = com.a1.notify.domain.NotificationStatus.PROCESSING "
            + "and n.processingStartedAt < :cutoff")
    List<NotificationEntity> findStuck(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationEntity n set n.recipientEmail = null, n.recipientPhone = null, n.variablesJson = null, "
            + "n.body = null where n.createdAt < :cutoff and n.status in (com.a1.notify.domain.NotificationStatus.SENT, "
            + "com.a1.notify.domain.NotificationStatus.DELIVERED, com.a1.notify.domain.NotificationStatus.CANCELLED) "
            + "and (n.recipientEmail is not null or n.recipientPhone is not null or n.variablesJson is not null)")
    int wipeContactData(@Param("cutoff") OffsetDateTime cutoff);

    @Query("select n.id from NotificationEntity n where n.createdAt < :cutoff and n.status <> "
            + "com.a1.notify.domain.NotificationStatus.PENDING and n.status <> com.a1.notify.domain.NotificationStatus.PROCESSING")
    List<UUID> findExpiredIds(@Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    default boolean isTerminal(NotificationStatus status) {
        return status.isTerminal();
    }
}
