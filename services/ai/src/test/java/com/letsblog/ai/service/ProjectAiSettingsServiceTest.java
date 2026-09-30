package com.letsblog.ai.service;

import com.letsblog.ai.domain.ProjectAiSettings;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectAiSettingsServiceの回帰テスト(issue #571/#574)。project_ai_settingsは初回書き込み時に
 * 遅延作成されること、未設定プロジェクトの読み取りはnull/falseへフォールバックすることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAiSettingsServiceTest {

    @Mock
    private ProjectAiSettingsRepository repository;

    private ProjectAiSettingsService service() {
        return new ProjectAiSettingsService(repository);
    }

    @Test
    void getLlmModel_未設定なら行が無くてもnullを返す() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertNull(service().getLlmModel(1L));
    }

    @Test
    void setLlmModel_行が無ければ新規作成して保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setLlmModel(1L, "gpt-4o");

        ArgumentCaptor<ProjectAiSettings> captor = ArgumentCaptor.forClass(ProjectAiSettings.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        ProjectAiSettings saved = captor.getValue();
        assertEquals(1L, saved.getProjectId());
        assertEquals("gpt-4o", saved.getLlmModel());
    }

    @Test
    void setLlmModel_既存行があれば更新する() {
        ProjectAiSettings existing = new ProjectAiSettings(1L);
        existing.setLlmModel("old-model");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setLlmModel(1L, "new-model");

        assertEquals("new-model", existing.getLlmModel());
    }

    @Test
    void hasBraveSearchApiKey_未設定行ならfalse() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertFalse(service().hasBraveSearchApiKey(1L));
    }

    @Test
    void setBraveSearchApiKeyEncrypted_暗号化済みバイト列を保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setBraveSearchApiKeyEncrypted(1L, new byte[]{1, 2, 3});

        ArgumentCaptor<ProjectAiSettings> captor = ArgumentCaptor.forClass(ProjectAiSettings.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getValue().hasBraveSearchApiKey());
    }

    @Test
    void getLlmProvider_設定済みならその値を返す() {
        ProjectAiSettings existing = new ProjectAiSettings(1L);
        existing.setLlmProvider("OPENAI");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));

        assertEquals("OPENAI", service().getLlmProvider(1L));
    }

    // ---- 接続先URLのプロジェクト単位上書き(issue #1503) ----

    private void stubNewRow() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProjectAiSettings savedRow() {
        ArgumentCaptor<ProjectAiSettings> captor = ArgumentCaptor.forClass(ProjectAiSettings.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void getOllamaBaseUrl_行が無ければnull() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertNull(service().getOllamaBaseUrl(1L));
        assertNull(service().getComfyuiBaseUrl(1L));
    }

    @Test
    void getBaseUrl_設定済みならその値を返す() {
        ProjectAiSettings existing = new ProjectAiSettings(1L);
        existing.setOllamaBaseUrl("http://gpu:11434/v1");
        existing.setComfyuiBaseUrl("https://comfy.example:8188");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));

        assertEquals("http://gpu:11434/v1", service().getOllamaBaseUrl(1L));
        assertEquals("https://comfy.example:8188", service().getComfyuiBaseUrl(1L));
    }

    @Test
    void setConnectionUrls_http_httpsのURLを保存する() {
        stubNewRow();

        service().setConnectionUrls(1L, "http://gpu:11434/v1", "https://comfy.example:8188");

        assertEquals("http://gpu:11434/v1", savedRow().getOllamaBaseUrl());
        assertEquals("https://comfy.example:8188", savedRow().getComfyuiBaseUrl());
    }

    @Test
    void setConnectionUrls_空文字は上書きを解除する() {
        ProjectAiSettings existing = new ProjectAiSettings(1L);
        existing.setOllamaBaseUrl("http://gpu:11434/v1");
        existing.setComfyuiBaseUrl("http://comfy:8188");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setConnectionUrls(1L, "", "   ");

        assertNull(existing.getOllamaBaseUrl());
        assertNull(existing.getComfyuiBaseUrl());
    }

    @Test
    void setConnectionUrls_nullの項目は変更しない() {
        ProjectAiSettings existing = new ProjectAiSettings(1L);
        existing.setOllamaBaseUrl("http://gpu:11434/v1");
        existing.setComfyuiBaseUrl("http://comfy:8188");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setConnectionUrls(1L, null, "http://other:8188");

        assertEquals("http://gpu:11434/v1", existing.getOllamaBaseUrl());
        assertEquals("http://other:8188", existing.getComfyuiBaseUrl());
    }

    @Test
    void setConnectionUrls_http_https以外のスキームは拒否して保存しない() {
        org.junit.jupiter.api.Assertions.assertThrows(InvalidConnectionUrlException.class,
                () -> service().setConnectionUrls(1L, "ftp://gpu:11434", null));
        org.junit.jupiter.api.Assertions.assertThrows(InvalidConnectionUrlException.class,
                () -> service().setConnectionUrls(1L, null, "javascript:alert(1)"));
        org.junit.jupiter.api.Assertions.assertThrows(InvalidConnectionUrlException.class,
                () -> service().setConnectionUrls(1L, null, "gpu:11434"));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void setConnectionUrls_空白や制御文字を含むURLは拒否して保存しない() {
        org.junit.jupiter.api.Assertions.assertThrows(InvalidConnectionUrlException.class,
                () -> service().setConnectionUrls(1L, "http://gpu :11434", null));
        org.junit.jupiter.api.Assertions.assertThrows(InvalidConnectionUrlException.class,
                () -> service().setConnectionUrls(1L, null, "http://comfy:8188\n"));
        org.junit.jupiter.api.Assertions.assertThrows(InvalidConnectionUrlException.class,
                () -> service().setConnectionUrls(1L, null, "http://comfy\u0000:8188"));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void setConnectionUrls_片方が不正なら他方も保存されない() {
        org.junit.jupiter.api.Assertions.assertThrows(InvalidConnectionUrlException.class,
                () -> service().setConnectionUrls(1L, "http://ok:11434/v1", "ftp://bad"));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    // ---- ChatGPT(OPENAI)のプロジェクト単位APIキー(issue #1506) ----

    @Test
    void hasOpenAiApiKey_未設定行ならfalse() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertFalse(service().hasOpenAiApiKey(1L));
    }

    @Test
    void hasOpenAiApiKey_空バイト列ならfalseで値があればtrue() {
        ProjectAiSettings settings = new ProjectAiSettings(1L);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(settings));

        assertFalse(service().hasOpenAiApiKey(1L));
        settings.setOpenAiApiKeyEncrypted(new byte[0]);
        assertFalse(service().hasOpenAiApiKey(1L));
        settings.setOpenAiApiKeyEncrypted(new byte[]{1});
        assertTrue(service().hasOpenAiApiKey(1L));
    }

    @Test
    void getOpenAiApiKeyEncrypted_未設定行ならnullで設定済みなら保存値を返す() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        assertNull(service().getOpenAiApiKeyEncrypted(1L));

        ProjectAiSettings settings = new ProjectAiSettings(2L);
        settings.setOpenAiApiKeyEncrypted(new byte[]{9});
        when(repository.findByProjectId(2L)).thenReturn(Optional.of(settings));
        assertEquals(1, service().getOpenAiApiKeyEncrypted(2L).length);
    }

    @Test
    void setOpenAiApiKeyEncrypted_行が無ければ作成して保存しnullで削除できる() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setOpenAiApiKeyEncrypted(1L, new byte[]{1, 2, 3});

        ArgumentCaptor<ProjectAiSettings> captor = ArgumentCaptor.forClass(ProjectAiSettings.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        ProjectAiSettings saved = captor.getValue();
        assertEquals(1L, saved.getProjectId());
        assertTrue(saved.hasOpenAiApiKey());

        when(repository.findByProjectId(1L)).thenReturn(Optional.of(saved));
        service().setOpenAiApiKeyEncrypted(1L, null);
        assertFalse(saved.hasOpenAiApiKey());
    }
}
