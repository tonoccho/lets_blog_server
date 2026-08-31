package com.letsblog.api.repository;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.domain.TagDesignSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagDesignSettingRepository extends JpaRepository<TagDesignSetting, Long> {
    Optional<TagDesignSetting> findByProjectIdAndTagType(Long projectId, EmbedTagType tagType);

    List<TagDesignSetting> findByProjectId(Long projectId);
}
