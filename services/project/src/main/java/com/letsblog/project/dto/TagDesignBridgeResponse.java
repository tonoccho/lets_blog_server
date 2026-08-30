package com.letsblog.project.dto;

/**
 * legacy-apiのContentBridgeController#tagDesign()(issue #576向け内部ブリッジ、content-serviceの
 * LegacyApiBridgeClient#resolveTagDesignが呼ぶ)が、[toc]/[blogcard]/[amazon]組み込みタグの
 * デザイン(色+カスタムHTMLテンプレート)をこのサービスへ中継するための内部API応答(issue #577 stage3)。
 * tag_design_settingsドメインの所有権はproject-serviceに移設済み(stage1)だが、content-serviceは
 * 引き続きlegacy-api経由でしか呼び出さない(#576のスコープ外のため変更しない)ため、legacy-api側の
 * ブリッジエンドポイント自体は残し、その実体をここへ委譲する。
 */
public record TagDesignBridgeResponse(
        String backgroundColor, String textColor, String accentColor, String customCss, String htmlTemplate) {

    public static TagDesignBridgeResponse of(TagDesignColors colors, String htmlTemplate) {
        return new TagDesignBridgeResponse(
                colors.backgroundColor(), colors.textColor(), colors.accentColor(), colors.customCss(), htmlTemplate);
    }
}
