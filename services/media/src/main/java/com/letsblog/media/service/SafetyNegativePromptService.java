package com.letsblog.media.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 画像生成のnegative promptへ、有効なカテゴリの安全側抑制語(safety negative prompt)を連結する
 * (issue #1085)。{@link ProhibitedContentFilterService}が入力プロンプトのキーワードブロックしか
 * 行わず、生成パラメータそのものには何も反映していなかった(#532の未達分)ことを引き取る。
 *
 * <p>{@link ProjectImageDefaultsResolver}と同様に、抑制語は{@code @Value}でアプリ全体の設定値
 * (環境変数で上書き可)として受け取る。カテゴリごとに独立しており、対応するブロックフラグが
 * {@code false}、あるいは設定値が空文字のときはそのカテゴリの語を一切連結しない。
 *
 * <p>連結はユーザー指定のnegativePromptを<b>置き換えず</b>、{@code ", "}区切りで末尾に足す。
 * 既に(大小文字を無視して)同じ語が含まれていれば、その語は連結しない。
 *
 * <p>連結先はnegative promptであり、{@link ProhibitedContentFilterService#check}が判定する
 * positive prompt側には一切触れない。ChatGPT経路(negative promptを送れない)へこの連結を
 * 適用しないことは、呼び出し側({@link ImageGenerationService#resolveParams})の責務とする
 * (このクラス自体はプロバイダを意識しない)。
 */
@Service
public class SafetyNegativePromptService {

    private final String sexualWords;
    private final String violentWords;
    private final String discriminatoryWords;

    public SafetyNegativePromptService(
            @Value("${app.safety-negative-prompt.sexual}") String sexualWords,
            @Value("${app.safety-negative-prompt.violent}") String violentWords,
            @Value("${app.safety-negative-prompt.discriminatory}") String discriminatoryWords) {
        this.sexualWords = sexualWords;
        this.violentWords = violentWords;
        this.discriminatoryWords = discriminatoryWords;
    }

    /**
     * 有効なカテゴリの安全側抑制語を{@code negativePrompt}へ連結して返す。
     *
     * @param negativePrompt        連結前のnegative prompt(未指定の解決済み値。null/空文字も許容する)
     * @param blockSexual           性的コンテンツのブロックが有効か
     * @param blockViolent          暴力的コンテンツのブロックが有効か
     * @param blockDiscriminatory   差別的表現のブロックが有効か
     * @return 連結後のnegative prompt
     */
    public String appendSafetyWords(
            String negativePrompt, boolean blockSexual, boolean blockViolent, boolean blockDiscriminatory) {
        String result = negativePrompt == null ? "" : negativePrompt;
        if (blockSexual) {
            result = appendCategory(result, sexualWords);
        }
        if (blockViolent) {
            result = appendCategory(result, violentWords);
        }
        if (blockDiscriminatory) {
            result = appendCategory(result, discriminatoryWords);
        }
        return result;
    }

    private String appendCategory(String base, String categoryWords) {
        if (categoryWords == null || categoryWords.isBlank()) {
            return base;
        }
        Set<String> existingLower = wordsLowerCase(base);
        List<String> toAppend = new ArrayList<>();
        for (String word : categoryWords.split(",")) {
            String trimmed = word.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (existingLower.add(trimmed.toLowerCase(Locale.ROOT))) {
                toAppend.add(trimmed);
            }
        }
        if (toAppend.isEmpty()) {
            return base;
        }
        String appended = String.join(", ", toAppend);
        return base.isBlank() ? appended : base + ", " + appended;
    }

    private Set<String> wordsLowerCase(String value) {
        Set<String> words = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return words;
        }
        for (String word : value.split(",")) {
            String trimmed = word.trim();
            if (!trimmed.isEmpty()) {
                words.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }
}
