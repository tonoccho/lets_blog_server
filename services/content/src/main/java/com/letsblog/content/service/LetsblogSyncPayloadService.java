package com.letsblog.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.client.ProjectBridgeClient.TagDesignResponse;
import com.letsblog.content.domain.EmbedTagType;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.LetsblogSyncPayloadResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPress の letsblog プラグインへ送る内容(issue #1558)を組み立てる: プロジェクトのカスタムタグ定義・
 * グローバルタグ定義・統合CSS(組み込みタグのデザインCSSを含む)・cssSelectorPrefix・組み込みタグの
 * デザインテンプレート。
 *
 * <p>同じ入力なら同じ文字列・同じハッシュになるよう、タグはスコープ→タグ名の順に並べ、キーの順は固定する。
 * ハッシュは送る文字列そのもののSHA-256で、プラグインは受け取った内容から同じ式で計算して保存し、
 * {@code wp letsblog status} の {@code sync_hash} で返す。
 */
@Service
public class LetsblogSyncPayloadService {

    private final CustomTagService customTagService;
    private final ProjectContentSettingsService projectContentSettingsService;
    private final ProjectBridgeClient projectBridgeClient;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public LetsblogSyncPayloadService(
            CustomTagService customTagService,
            ProjectContentSettingsService projectContentSettingsService,
            ProjectBridgeClient projectBridgeClient,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this.customTagService = customTagService;
        this.projectContentSettingsService = projectContentSettingsService;
        this.projectBridgeClient = projectBridgeClient;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    public LetsblogSyncPayloadResponse build(Long projectId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("cssSelectorPrefix", projectContentSettingsService.resolveCssSelectorPrefix(projectId));
        payload.put("cssBundle", customTagService.buildCssBundle(projectId));
        payload.put("customTags", customTags(projectId));
        payload.put("tagDesigns", tagDesigns(projectId));
        String json = toJson(payload);
        return new LetsblogSyncPayloadResponse(json, sha256(json));
    }

    private List<Map<String, Object>> customTags(Long projectId) {
        List<Map<String, Object>> tags = new ArrayList<>();
        customTagService.list(projectId).stream()
                .sorted(Comparator.comparing((CustomTagResponse t) -> scope(t)).thenComparing(CustomTagResponse::tagName))
                .forEach(tag -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("scope", scope(tag));
                    entry.put("tagName", tag.tagName());
                    entry.put("tagFormat", tag.tagFormat().name());
                    entry.put("htmlTemplate", tag.htmlTemplate());
                    entry.put("cssContent", tag.cssContent());
                    tags.add(entry);
                });
        return tags;
    }

    private static String scope(CustomTagResponse tag) {
        return tag.projectId() == null ? "GLOBAL" : "PROJECT";
    }

    private List<Map<String, Object>> tagDesigns(Long projectId) {
        String bearer = currentActorService.getAuthorizationHeader();
        List<Map<String, Object>> designs = new ArrayList<>();
        for (EmbedTagType tagType : EmbedTagType.values()) {
            TagDesignResponse design = projectBridgeClient.resolveTagDesign(projectId, tagType.name(), bearer);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tagType", tagType.name());
            entry.put("backgroundColor", design.backgroundColor());
            entry.put("textColor", design.textColor());
            entry.put("accentColor", design.accentColor());
            entry.put("customCss", design.customCss());
            entry.put("htmlTemplate", design.htmlTemplate());
            designs.add(entry);
        }
        return designs;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("同期内容をJSONにできません", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }
}
