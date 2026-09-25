package com.letsblog.project.repository;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.TagDesignSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagDesignSettingRepository extends JpaRepository<TagDesignSetting, Long> {
    Optional<TagDesignSetting> findByProjectIdAndTagType(Long projectId, EmbedTagType tagType);

    List<TagDesignSetting> findByProjectId(Long projectId);

    /**
     * グローバル既定行(project_id IS NULL)を引く(#763)。
     *
     * <p>{@code findByProjectIdAndTagType(null, tagType)}では引けない。Spring Data JPAが
     * {@code project_id = NULL}を生成し、NULL同士の比較は常にUNKNOWNで何にも一致しないため。
     * 一意性はマイグレーションV2の{@code uq_tag_design_settings_scope_tag}が担保する。
     */
    Optional<TagDesignSetting> findByProjectIdIsNullAndTagType(EmbedTagType tagType);

    List<TagDesignSetting> findByProjectIdIsNull();
}
