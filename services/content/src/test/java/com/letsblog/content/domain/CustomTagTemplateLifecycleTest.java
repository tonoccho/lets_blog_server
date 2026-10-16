package com.letsblog.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 永続化の直前に呼ばれる {@code @PrePersist} / {@code @PreUpdate} のフックを、実物のエンティティで通す。 */
class CustomTagTemplateLifecycleTest {

    @Test
    void 作成時に未設定のversionと公開状態へ既定値を入れ_作成日時と更新日時を揃える() {
        CustomTagTemplate template = new CustomTagTemplate();

        template.onCreate();

        assertThat(template.getVersion()).isEqualTo(1);
        assertThat(template.getIsPublished()).isFalse();
        assertThat(template.getCreatedAt()).isNotNull().isEqualTo(template.getUpdatedAt());
    }

    @Test
    void 作成時にversionと公開状態が明示的にnullでも既定値へ直す() {
        CustomTagTemplate template = new CustomTagTemplate();
        template.setVersion(null);
        template.setIsPublished(null);

        template.onCreate();

        assertThat(template.getVersion()).isEqualTo(1);
        assertThat(template.getIsPublished()).isFalse();
    }

    @Test
    void 作成時に設定済みのversionと公開状態は上書きしない() {
        CustomTagTemplate template = new CustomTagTemplate();
        template.setVersion(4);
        template.setIsPublished(true);

        template.onCreate();

        assertThat(template.getVersion()).isEqualTo(4);
        assertThat(template.getIsPublished()).isTrue();
    }

    @Test
    void 更新時は更新日時だけを進める() {
        CustomTagTemplate template = new CustomTagTemplate();
        template.onCreate();
        LocalDateTime created = template.getCreatedAt();
        template.setUpdatedAt(created.minusDays(1));

        template.onUpdate();

        assertThat(template.getUpdatedAt()).isAfter(created.minusDays(1));
        assertThat(template.getCreatedAt()).isEqualTo(created);
    }
}
