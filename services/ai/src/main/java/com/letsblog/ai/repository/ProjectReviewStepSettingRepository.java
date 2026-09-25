package com.letsblog.ai.repository;

import com.letsblog.ai.domain.ProjectReviewStepSetting;
import com.letsblog.ai.domain.ReviewStepKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectReviewStepSettingRepository extends JpaRepository<ProjectReviewStepSetting, Long> {
    Optional<ProjectReviewStepSetting> findByProjectIdAndStepKey(Long projectId, ReviewStepKey stepKey);

    List<ProjectReviewStepSetting> findByProjectId(Long projectId);
}
