package com.letsblog.project.repository;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.TagDesignSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagDesignSettingRepository extends JpaRepository<TagDesignSetting, Long> {
    Optional<TagDesignSetting> findByProjectIdAndTagType(Long projectId, EmbedTagType tagType);

    List<TagDesignSetting> findByProjectId(Long projectId);
}
