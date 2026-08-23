package com.letsblog.api.service;

import com.letsblog.api.ai.ImageProvider;
import com.letsblog.api.dto.ImageProviderListResponse;
import com.letsblog.api.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImageModelServiceの回帰テスト(issue #571)。データがproject_image_settings(ProjectImageSettingsService)
 * に移った後も、未選択時のCOMFYUIへのフォールバックとprojectId未指定時の挙動が維持されていることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ImageModelServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectImageSettingsService projectImageSettingsService;

    private ImageModelService service() {
        return new ImageModelService(projectRepository, projectImageSettingsService);
    }

    @Test
    void getSelectedProvider_projectId未指定ならCOMFYUIを返す() {
        assertEquals(ImageProvider.COMFYUI, service().getSelectedProvider(null));
    }

    @Test
    void getSelectedProvider_未選択ならCOMFYUIを返す() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectImageSettingsService.getImageProvider(1L)).thenReturn(null);

        assertEquals(ImageProvider.COMFYUI, service().getSelectedProvider(1L));
    }

    @Test
    void getSelectedProvider_選択済みならその値を返す() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectImageSettingsService.getImageProvider(1L)).thenReturn("CHATGPT");

        assertEquals(ImageProvider.CHATGPT, service().getSelectedProvider(1L));
    }

    @Test
    void getSelectedProvider_存在しないプロジェクトは例外() {
        when(projectRepository.existsById(99L)).thenReturn(false);

        assertThrows(ProjectNotFoundException.class, () -> service().getSelectedProvider(99L));
    }

    @Test
    void selectProvider_ProjectImageSettingsServiceへ保存する() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectImageSettingsService.getImageProvider(1L)).thenReturn("CHATGPT");

        ImageProviderListResponse response = service().selectProvider(1L, "CHATGPT");

        verify(projectImageSettingsService).setImageProvider(1L, "CHATGPT");
        assertEquals("CHATGPT", response.selected());
    }
}
