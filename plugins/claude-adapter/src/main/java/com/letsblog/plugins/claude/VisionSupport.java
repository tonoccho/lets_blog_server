package com.letsblog.plugins.claude;

import java.util.List;
import java.util.Locale;

/**
 * Claude のモデルが画像入力に対応するかの判定。
 *
 * <p>services/ai の {@code VisionSupport} の Claude の分岐を移したもの(CONTRACTS.md §4.4)。
 * Anthropic の一覧 API にはモデルごとの対応可否が無いため、モデル名で判定する。
 * 旧世代({@code claude-1} / {@code claude-2} / {@code claude-instant})は非対応、
 * それ以外の {@code claude-} は対応とみなす。
 */
final class VisionSupport {

    private static final List<String> EXCLUDED_PREFIXES = List.of("claude-1", "claude-2", "claude-instant");

    private VisionSupport() {
    }

    static boolean supportsImageInput(String model) {
        if (model == null) {
            return false;
        }
        String name = model.strip().toLowerCase(Locale.ROOT);
        return name.startsWith("claude-") && EXCLUDED_PREFIXES.stream().noneMatch(name::startsWith);
    }
}
