package com.letsblog.media.service;

import com.letsblog.media.domain.ProjectImageSettings;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成まわりの既定値の解決(プロジェクト設定 > サーバー既定値)。
 *
 * <p>issue #1819: {@code ProjectServiceClient}・{@code ProjectImageSettingsService}は実物で、project-serviceを
 * フェイクHTTPサーバー、{@code project_image_settings}をリポジトリのmockが演じる。
 */
@DisplayName("ProjectImageDefaultsResolver: プロジェクト設定とサーバー既定値の解決")
class ProjectImageDefaultsResolverTest {

    private final MediaWorld world = new MediaWorld();
    private final ProjectImageDefaultsResolver resolver = world.defaultsResolver;

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("プロジェクトを指定しなければ、project-serviceにもDBにも問い合わせず、サーバー既定値を返す")
    void プロジェクト未指定() {
        assertEquals(MediaWorld.GLOBAL_NEGATIVE, resolver.resolveDefaultNegativePrompt(null));
        assertEquals(MediaWorld.GLOBAL_QUALITY, resolver.resolveDefaultQualityPrompt(null));
        assertEquals(1024, resolver.resolveDefaultGeneratedImageWidth(null));
        assertEquals(768, resolver.resolveDefaultGeneratedImageHeight(null));
        assertEquals(1300, resolver.resolveArticleImageLongEdgePx(null));
        assertFalse(resolver.resolveBlockSexualContent(null));
        assertFalse(resolver.resolveBlockViolentContent(null));
        assertFalse(resolver.resolveBlockDiscriminatoryContent(null));
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    @DisplayName("プロジェクトに設定が無ければ、サーバー既定値を返す")
    void 設定が無い() {
        assertEquals(MediaWorld.GLOBAL_NEGATIVE, resolver.resolveDefaultNegativePrompt(1L));
        assertEquals(1024, resolver.resolveDefaultGeneratedImageWidth(1L));
        assertFalse(resolver.resolveBlockSexualContent(1L));
    }

    @Test
    @DisplayName("プロジェクトに設定があれば、その値を返す")
    void 設定がある() {
        ProjectImageSettings settings = world.settingsFor(1L);
        settings.setDefaultNegativePrompt("neg");
        settings.setDefaultQualityPrompt("qual");
        settings.setDefaultGeneratedImageWidth(640);
        settings.setDefaultGeneratedImageHeight(480);
        settings.setDefaultArticleImageLongEdgePx(900);
        settings.setBlockSexualContent(true);
        settings.setBlockViolentContent(true);
        settings.setBlockDiscriminatoryContent(true);

        assertEquals("neg", resolver.resolveDefaultNegativePrompt(1L));
        assertEquals("qual", resolver.resolveDefaultQualityPrompt(1L));
        assertEquals(640, resolver.resolveDefaultGeneratedImageWidth(1L));
        assertEquals(480, resolver.resolveDefaultGeneratedImageHeight(1L));
        assertEquals(900, resolver.resolveArticleImageLongEdgePx(1L));
        assertTrue(resolver.resolveBlockSexualContent(1L));
        assertTrue(resolver.resolveBlockViolentContent(1L));
        assertTrue(resolver.resolveBlockDiscriminatoryContent(1L));
    }

    @Test
    @DisplayName("プロジェクトの文字列設定が空白だけなら、サーバー既定値を返す")
    void 空白の設定() {
        ProjectImageSettings settings = world.settingsFor(1L);
        settings.setDefaultNegativePrompt("   ");
        settings.setDefaultQualityPrompt("");

        assertEquals(MediaWorld.GLOBAL_NEGATIVE, resolver.resolveDefaultNegativePrompt(1L));
        assertEquals(MediaWorld.GLOBAL_QUALITY, resolver.resolveDefaultQualityPrompt(1L));
    }

    @Test
    @DisplayName("プロジェクトが存在しなければ例外(project-serviceが404)")
    void プロジェクトが存在しない() {
        world.projectMissing(1L);

        assertThrows(ProjectNotFoundException.class, () -> resolver.resolveDefaultNegativePrompt(1L));
    }
}
