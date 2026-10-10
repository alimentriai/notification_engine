package com.a1.notify.repository;

import com.a1.notify.domain.ProjectEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {
    Optional<ProjectEntity> findByCode(String code);
}
