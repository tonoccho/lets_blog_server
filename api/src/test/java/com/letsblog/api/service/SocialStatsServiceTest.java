package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.buffer.BufferApiException;
import com.letsblog.api.buffer.BufferClient;
import com.letsblog.api.buffer.BufferUpdateStatistics;
import com.letsblog.api.domain.BufferPost;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.SocialStatsResponse;
import com.letsblog.api.repository.BufferPostRepository;
import com.letsblog.api.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SocialStatsServiceの回帰テスト(issue #390)。Buffer連携無効/本番サイト未紐付け/送信済み投稿が無い場合に
 * eligible=falseを返しBuffer APIへ問い合わせないこと、複数投稿・複数update IDにまたがる統計を
 * 合算すること、取得失敗時にeligible=trueのままerrorMessageを設定することを検証する。
 * Buffer連携設定はプロジェクト単位(issue #402)のため、ProjectApiKeyService#resolveBufferSettingsを
 * モックして与える。
 */
@ExtendWith(MockitoExtension.class)
class SocialStatsServiceTest {

    private static final String ACCESS_TOKEN = "test-access-token";

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private BufferPostRepository bufferPostRepository;
    @Mock
    private BufferClient bufferClient;
    @Mock
    private ProjectApiKeyService projectApiKeyService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SocialStatsService service() {
        return new SocialStatsService(
                projectRepository, bufferPostRepository, bufferClient, projectApiKeyService, adminAuthorizationService,
                objectMapper);
    }

    private void stubBufferEnabled(boolean enabled) {
        lenient().when(projectApiKeyService.resolveBufferSettings(1L)).thenReturn(
                new ProjectApiKeyService.BufferSettings(enabled, ACCESS_TOKEN, List.of(), 5, "{title} {url}"));
    }

    private Project projectWithProductionSite(Long siteId) {
        Project project = new Project();
        project.setId(1L);
        project.setProductionSiteId(siteId);
        return project;
    }

    private BufferPost sentPost(Long id, String... updateIds) {
        BufferPost post = new BufferPost();
        post.setId(id);
        post.setStatus("sent");
        post.setResultPayload("{\"bufferUpdateIds\":[" +
                String.join(",", java.util.Arrays.stream(updateIds).map(u -> "\"" + u + "\"").toList()) + "]}");
        return post;
    }

    @Test
    void getStats_Buffer無効なら未対象でAPIを呼ばない() {
        Project project = projectWithProductionSite(99L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        stubBufferEnabled(false);

        SocialStatsResponse response = service().getStats(1L);

        assertFalse(response.eligible());
        verify(bufferClient, never()).getUpdateStatistics(any(), any());
    }

    @Test
    void getStats_本番サイト未紐付けなら未対象でAPIを呼ばない() {
        Project project = new Project();
        project.setId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        stubBufferEnabled(true);

        SocialStatsResponse response = service().getStats(1L);

        assertFalse(response.eligible());
    }

    @Test
    void getStats_送信済み投稿が無ければ未対象() {
        Project project = projectWithProductionSite(99L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        stubBufferEnabled(true);
        when(bufferPostRepository.findBySiteIdAndStatus(99L, "sent")).thenReturn(List.of());

        SocialStatsResponse response = service().getStats(1L);

        assertFalse(response.eligible());
    }

    @Test
    void getStats_複数投稿_複数updateIdの統計を合算する() {
        Project project = projectWithProductionSite(99L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        stubBufferEnabled(true);
        BufferPost post1 = sentPost(1L, "upd-1", "upd-2");
        BufferPost post2 = sentPost(2L, "upd-3");
        when(bufferPostRepository.findBySiteIdAndStatus(99L, "sent")).thenReturn(List.of(post1, post2));
        when(bufferClient.getUpdateStatistics("upd-1", ACCESS_TOKEN)).thenReturn(new BufferUpdateStatistics(1, 2, 3, 4));
        when(bufferClient.getUpdateStatistics("upd-2", ACCESS_TOKEN)).thenReturn(new BufferUpdateStatistics(10, 20, 30, 40));
        when(bufferClient.getUpdateStatistics("upd-3", ACCESS_TOKEN)).thenReturn(new BufferUpdateStatistics(100, 200, 300, 400));

        SocialStatsResponse response = service().getStats(1L);

        assertTrue(response.eligible());
        assertEquals(2, response.postCount());
        assertEquals(222L, response.likes());
        assertEquals(444L, response.shares());
        assertEquals(333L, response.comments());
        assertEquals(111L, response.clicks());
    }

    @Test
    void getStats_取得に失敗したら対象のままerrorMessageを設定する() {
        Project project = projectWithProductionSite(99L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        stubBufferEnabled(true);
        BufferPost post = sentPost(1L, "upd-1");
        when(bufferPostRepository.findBySiteIdAndStatus(99L, "sent")).thenReturn(List.of(post));
        when(bufferClient.getUpdateStatistics("upd-1", ACCESS_TOKEN)).thenThrow(new BufferApiException("APIエラー"));

        SocialStatsResponse response = service().getStats(1L);

        assertTrue(response.eligible());
        assertEquals("APIエラー", response.errorMessage());
    }

    @Test
    void getStats_resultPayload解析に失敗した投稿はスキップする() {
        Project project = projectWithProductionSite(99L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        stubBufferEnabled(true);
        BufferPost invalidPost = new BufferPost();
        invalidPost.setId(1L);
        invalidPost.setStatus("sent");
        invalidPost.setResultPayload("not-json");
        when(bufferPostRepository.findBySiteIdAndStatus(99L, "sent")).thenReturn(List.of(invalidPost));

        SocialStatsResponse response = service().getStats(1L);

        assertTrue(response.eligible());
        assertEquals(1, response.postCount());
        assertEquals(0L, response.likes());
    }

    @Test
    void getStats_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> service().getStats(1L));
    }
}
