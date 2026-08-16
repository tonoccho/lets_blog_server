package com.letsblog.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.buffer.BufferClient;
import com.letsblog.api.buffer.BufferUpdateStatistics;
import com.letsblog.api.domain.BufferPost;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.SocialStatsResponse;
import com.letsblog.api.repository.BufferPostRepository;
import com.letsblog.api.repository.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * プロジェクトダッシュボードのソーシャル統計ウィジェット(issue #390)向けに、Buffer経由で送信済みの
 * 投稿(buffer_posts、issue #379)の統計を集計する。issue #390のコメントでスコープを
 * 「Buffer経由で投稿した記事の投稿別エンゲージメント統計(いいね/シェア/コメント/クリック)」に絞っており、
 * フォロワー数等アカウントレベルの統計は対象外。Buffer連携が無効(プロジェクト単位の設定、issue #402)、
 * または対象の送信済み投稿が(本番サイトに)無い場合はeligible=falseを返しBuffer APIへは問い合わせない
 * (GoogleAnalyticsReportService/AdSenseReportServiceと同じ、本番サイトのみを対象にするgatingの方針)。
 */
@Service
@Slf4j
public class SocialStatsService {

    private static final String SENT_STATUS = "sent";

    private final ProjectRepository projectRepository;
    private final BufferPostRepository bufferPostRepository;
    private final BufferClient bufferClient;
    private final ProjectApiKeyService projectApiKeyService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;

    public SocialStatsService(
            ProjectRepository projectRepository,
            BufferPostRepository bufferPostRepository,
            BufferClient bufferClient,
            ProjectApiKeyService projectApiKeyService,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.bufferPostRepository = bufferPostRepository;
        this.bufferClient = bufferClient;
        this.projectApiKeyService = projectApiKeyService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SocialStatsResponse getStats(Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Project project = getProject(projectId);
        ProjectApiKeyService.BufferSettings settings = projectApiKeyService.resolveBufferSettings(projectId);
        if (!settings.enabled() || project.getProductionSiteId() == null) {
            return SocialStatsResponse.notEligible();
        }
        List<BufferPost> posts = bufferPostRepository.findBySiteIdAndStatus(project.getProductionSiteId(), SENT_STATUS);
        if (posts.isEmpty()) {
            return SocialStatsResponse.notEligible();
        }
        try {
            long likes = 0;
            long shares = 0;
            long comments = 0;
            long clicks = 0;
            for (BufferPost post : posts) {
                for (String updateId : extractUpdateIds(post)) {
                    BufferUpdateStatistics stats = bufferClient.getUpdateStatistics(updateId, settings.accessToken());
                    likes += stats.favorites();
                    shares += stats.shares();
                    comments += stats.comments();
                    clicks += stats.clicks();
                }
            }
            return SocialStatsResponse.of(posts.size(), likes, shares, comments, clicks);
        } catch (RuntimeException e) {
            log.warn("ソーシャル統計の取得に失敗しました(project={}): {}", projectId, e.getMessage());
            return SocialStatsResponse.error(e.getMessage());
        }
    }

    private List<String> extractUpdateIds(BufferPost post) {
        try {
            JsonNode node = objectMapper.readTree(post.getResultPayload());
            List<String> ids = new ArrayList<>();
            for (JsonNode idNode : node.path("bufferUpdateIds")) {
                ids.add(idNode.asText());
            }
            return ids;
        } catch (Exception e) {
            log.warn("BufferPost(id={})のresultPayload解析に失敗しました: {}", post.getId(), e.getMessage());
            return List.of();
        }
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
