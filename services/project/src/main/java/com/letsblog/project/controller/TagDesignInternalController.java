package com.letsblog.project.controller;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.TagDesignBridgeResponse;
import com.letsblog.project.dto.TagDesignColors;
import com.letsblog.project.service.TagDesignSettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiのContentBridgeController#tagDesign()(issue #577 stage3)が中継する、
 * [toc]/[blogcard]/[amazon]組み込みタグデザイン(色+カスタムHTMLテンプレート)の内部API。
 *
 * <p>tag_design_settingsドメインの所有権はstage1でこのサービスへ移設済みだが、唯一の利用元である
 * content-service(issue #576)は{@code /api/internal/content/tag-design/{tagType}}
 * (legacy-apiのContentBridgeController)経由でしか呼び出さない(content-service側の変更は#576の
 * スコープであり#577スコープ外)。そのためlegacy-api側のブリッジエンドポイント自体は残し、
 * その実体をこの内部APIへ委譲する({@code SiteCredentialsInternalController}と同じ方針。
 * project-serviceのSecurityConfigによる{@code /api/internal/**}の認証必須以上の追加認可は行わない)。
 */
@RestController
public class TagDesignInternalController {

    private final TagDesignSettingService tagDesignSettingService;

    public TagDesignInternalController(TagDesignSettingService tagDesignSettingService) {
        this.tagDesignSettingService = tagDesignSettingService;
    }

    /**
     * projectIdは必須ではない(issue #760)。プロジェクトに紐付いていないサイトへの公開では
     * 呼び出し元(content-service→legacy-api)がprojectId=nullで解決を要求し、URIテンプレート展開の
     * 結果{@code ?projectId=}(空文字)として届く。required=trueのままだとSpringが空文字をLongへ
     * 変換した結果のnullを「パラメータ未指定」と判定して400になるため、明示的にrequired=falseとし、
     * {@link TagDesignSettingService}側でグローバル既定へフォールバックさせる。
     */
    @GetMapping("/api/internal/project/tag-design/{tagType}")
    public TagDesignBridgeResponse tagDesign(
            @PathVariable EmbedTagType tagType, @RequestParam(required = false) Long projectId) {
        TagDesignColors colors = tagDesignSettingService.resolveColors(projectId, tagType);
        String htmlTemplate = tagDesignSettingService.resolveHtmlTemplate(projectId, tagType);
        return TagDesignBridgeResponse.of(colors, htmlTemplate);
    }
}
