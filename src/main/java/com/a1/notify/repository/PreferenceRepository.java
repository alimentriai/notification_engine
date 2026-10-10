package com.a1.notify.repository;

import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.PreferenceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceRepository extends JpaRepository<PreferenceEntity, Long> {
    List<PreferenceEntity> findByProjectIdAndUserRef(Long projectId, String userRef);

    Optional<PreferenceEntity> findByProjectIdAndUserRefAndChannelAndCategory(
            Long projectId, String userRef, Channel channel, Category category);
}
