package com.a1.notify.repository;

import com.a1.notify.domain.OtpChallengeEntity;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OtpChallengeRepository extends JpaRepository<OtpChallengeEntity, UUID> {

    Optional<OtpChallengeEntity> findByIdAndProjectId(UUID id, Long projectId);

    Optional<OtpChallengeEntity> findFirstByProjectIdAndRecipientHashOrderByCreatedAtDesc(Long projectId, String recipientHash);

    long countByProjectIdAndRecipientHashAndCreatedAtAfter(Long projectId, String recipientHash, OffsetDateTime since);

    /** Atomic attempt counter so concurrent guesses cannot exceed the limit. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OtpChallengeEntity o set o.attempts = o.attempts + 1 where o.id = :id and o.attempts < o.maxAttempts "
            + "and o.consumedAt is null")
    int incrementAttempts(@Param("id") UUID id);

    /** Single-use guarantee: only one verifier can consume a challenge. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OtpChallengeEntity o set o.consumedAt = :now where o.id = :id and o.consumedAt is null")
    int consume(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying
    void deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
