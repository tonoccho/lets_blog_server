package com.letsblog.media.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 画像生成プロンプトに含まれる不適切コンテンツのキーワードを検出する(issue #532、Phase A: キーワードブロック方式)。
 * プロンプト文字列に対する簡易フィルタであり、生成された画像の内容そのものは判定できない。
 * ComfyUIにはデフォルトで検閲機能が無いため主にComfyUI向けの安全策となるが、プロバイダーを問わず
 * プロンプト送信前に一律で適用する(AiAssistService#generateImage)。
 */
@Service
public class ProhibitedContentFilterService {

    private static final List<String> SEXUAL_KEYWORDS = List.of(
            "nude", "naked", "nsfw", "porn", "pornographic", "hentai", "erotic", "sexual intercourse",
            "ヌード", "全裸", "裸体", "性行為", "エロ", "ポルノ", "児童ポルノ", "わいせつ"
    );

    private static final List<String> VIOLENT_KEYWORDS = List.of(
            "gore", "gory", "mutilation", "mutilated", "dismembered", "torture", "beheading", "self-harm", "suicide method",
            "血まみれ", "流血", "惨殺", "拷問", "自殺方法", "自傷行為", "虐殺", "斬首"
    );

    private static final List<String> DISCRIMINATORY_KEYWORDS = List.of(
            "nazi", "hate speech", "racial slur", "white supremacist", "ethnic cleansing",
            "差別的表現", "ヘイトスピーチ", "人種差別", "民族浄化"
    );

    /**
     * プロンプトを判定し、有効なカテゴリに該当するキーワードが含まれていればブロックする。
     *
     * @throws ProhibitedContentException 有効化されたカテゴリのキーワードに該当した場合
     */
    public void check(String prompt, boolean blockSexual, boolean blockViolent, boolean blockDiscriminatory) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (blockSexual) {
            requireNoMatch(normalized, SEXUAL_KEYWORDS, "性的コンテンツ");
        }
        if (blockViolent) {
            requireNoMatch(normalized, VIOLENT_KEYWORDS, "暴力的コンテンツ");
        }
        if (blockDiscriminatory) {
            requireNoMatch(normalized, DISCRIMINATORY_KEYWORDS, "差別的表現");
        }
    }

    private void requireNoMatch(String normalizedPrompt, List<String> keywords, String categoryLabel) {
        for (String keyword : keywords) {
            if (normalizedPrompt.contains(keyword.toLowerCase(Locale.ROOT))) {
                throw new ProhibitedContentException(
                        categoryLabel + "に該当する可能性のあるキーワードが含まれているため、画像生成をブロックしました。");
            }
        }
    }
}
