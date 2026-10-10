package com.a1.notify.repository;

import com.a1.notify.domain.Channel;
import com.a1.notify.domain.TemplateEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<TemplateEntity, Long> {

    Optional<TemplateEntity> findFirstByProjectIdAndCodeAndChannelAndLocaleAndActiveTrueOrderByVersionDesc(
            Long projectId, String code, Channel channel, String locale);

    Optional<TemplateEntity> findByProjectIdAndCodeAndChannelAndLocaleAndVersion(
            Long projectId, String code, Channel channel, String locale, int version);

    Optional<TemplateEntity> findFirstByProjectIdAndCodeAndChannelAndLocaleOrderByVersionDesc(
            Long projectId, String code, Channel channel, String locale);

    List<TemplateEntity> findByProjectIdOrderByCodeAscVersionDesc(Long projectId);
}
