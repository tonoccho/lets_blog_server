package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.buffer.BufferApiException;
import com.letsblog.api.buffer.BufferClient;
import com.letsblog.api.buffer.BufferUpdate;
import com.letsblog.api.domain.BufferPost;
import com.letsblog.api.repository.BufferPostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BufferNotificationServiceの回帰テスト。無効化時の何もしない挙動、成功時のジョブ記録、
 * リトライ挙動(成功するまで/全て失敗した場合)を検証する。
 */
@ExtendWith(MockitoExtension.class)
class BufferNotificationServiceTest {

    @Mock
    private BufferClient bufferClient;
    @Mock
    private BufferPostRepository bufferPostRepository;

    private BufferNotificationService service(boolean enabled, String profileIdsCsv) {
        return new BufferNotificationService(
                bufferClient, bufferPostRepository, new ObjectMapper(),
                enabled, profileIdsCsv, 5, "{title} {url}", 0L);
    }

    @Test
    void notifyAsync_無効時は何もしない() {
        BufferNotificationService service = service(false, "profile-1");

        service.notifyAsync(1L, 2L, "タイトル", "https://example.com/1");

        verify(bufferPostRepository, never()).save(any());
        verify(bufferClient, never()).createUpdate(any(), any(), any());
    }

    @Test
    void notifyAsync_プロファイル未設定時は何もしない() {
        BufferNotificationService service = service(true, "");

        service.notifyAsync(1L, 2L, "タイトル", "https://example.com/1");

        verify(bufferPostRepository, never()).save(any());
        verify(bufferClient, never()).createUpdate(any(), any(), any());
    }

    @Test
    void notifyAsync_成功時はジョブをsentとして記録する() {
        BufferNotificationService service = service(true, "profile-1,profile-2");
        when(bufferClient.createUpdate(eq(List.of("profile-1", "profile-2")), anyString(), any(Instant.class)))
                .thenReturn(List.of(new BufferUpdate("upd-1", "profile-1"), new BufferUpdate("upd-2", "profile-2")));

        service.notifyAsync(10L, 20L, "新しい記事", "https://example.com/new-article");

        ArgumentCaptor<BufferPost> captor = ArgumentCaptor.forClass(BufferPost.class);
        verify(bufferPostRepository, times(2)).save(captor.capture());
        BufferPost saved = captor.getValue();
        assertEquals(10L, saved.getPostId());
        assertEquals(20L, saved.getSiteId());
        assertEquals("sent", saved.getStatus());
        assertTrue(saved.getResultPayload().contains("upd-1"));
        assertTrue(saved.getResultPayload().contains("upd-2"));

        verify(bufferClient).createUpdate(eq(List.of("profile-1", "profile-2")), eq("新しい記事 https://example.com/new-article"),
                any(Instant.class));
    }

    @Test
    void notifyAsync_一時的な失敗後に成功すればsentになる() {
        BufferNotificationService service = service(true, "profile-1");
        when(bufferClient.createUpdate(anyList(), anyString(), any(Instant.class)))
                .thenThrow(new BufferApiException("一時的なエラー"))
                .thenReturn(List.of(new BufferUpdate("upd-1", "profile-1")));

        service.notifyAsync(1L, 2L, "タイトル", "https://example.com/1");

        verify(bufferClient, times(2)).createUpdate(anyList(), anyString(), any(Instant.class));
        ArgumentCaptor<BufferPost> captor = ArgumentCaptor.forClass(BufferPost.class);
        verify(bufferPostRepository, times(2)).save(captor.capture());
        assertEquals("sent", captor.getValue().getStatus());
    }

    @Test
    void notifyAsync_全て失敗すればfailedとして記録する() {
        BufferNotificationService service = service(true, "profile-1");
        when(bufferClient.createUpdate(anyList(), anyString(), any(Instant.class)))
                .thenThrow(new BufferApiException("認証エラー"));

        service.notifyAsync(1L, 2L, "タイトル", "https://example.com/1");

        verify(bufferClient, times(3)).createUpdate(anyList(), anyString(), any(Instant.class));
        ArgumentCaptor<BufferPost> captor = ArgumentCaptor.forClass(BufferPost.class);
        verify(bufferPostRepository, times(2)).save(captor.capture());
        BufferPost last = captor.getValue();
        assertEquals("failed", last.getStatus());
        assertTrue(last.getResultPayload().contains("認証エラー"));
    }
}
