package com.letsblog.ai.ai;

import java.util.List;
import java.util.Locale;

/**
 * プロバイダ/モデルが画像入力(vision)に対応しているかの判定(issue #1600)。
 *
 * <p>各プロバイダのAPIにはモデルごとの対応可否を問い合わせる手段が無い(Ollamaの/api/showは
 * 一部のバージョンでしか返さない)ため、<b>モデル名の許可リスト</b>で判定する。
 * リストに無いモデルは「非対応」として扱い、呼び出し側は画像入力を伴う処理(アップロード画像の
 * AIタグ付け)を省略する。対応モデルが増えたらここへ足す。
 *
 * <p>比較は小文字化したモデル名で行う。Ollamaのモデル名はタグ付き({@code llava:7b})で、
 * 派生名({@code llava-llama3}など)もあるため前方一致ではなく部分一致にしている。
 */
public final class VisionSupport {

    /** OpenAI: 画像入力に対応するモデル名の接頭辞。 */
    private static final List<String> OPENAI_PREFIXES = List.of(
            "gpt-4o", "chatgpt-4o", "gpt-4.1", "gpt-4.5", "gpt-5", "gpt-4-turbo", "gpt-4-vision",
            "o1", "o3", "o4");

    /** OpenAI: 接頭辞に当てはまるが画像入力に対応しないモデル(推論系のmini/preview)。 */
    private static final List<String> OPENAI_EXCLUDED_PREFIXES = List.of("o1-mini", "o1-preview", "o3-mini");

    /** Claude: 画像入力に対応しない旧世代。それ以外の claude- は対応とみなす。 */
    private static final List<String> CLAUDE_EXCLUDED_PREFIXES = List.of(
            "claude-1", "claude-2", "claude-instant");

    /** Ollama: 画像入力に対応するモデル名の部分文字列。 */
    private static final List<String> OLLAMA_SUBSTRINGS = List.of(
            "llava", "vision", "minicpm-v", "moondream", "gemma3", "-vl", "qwen2.5vl", "llama4",
            "mistral-small3");

    /** Ollama: 部分文字列に当てはまるが画像入力に対応しないモデル(gemma3の1Bはテキスト専用)。 */
    private static final List<String> OLLAMA_EXCLUDED_SUBSTRINGS = List.of("gemma3:1b");

    private VisionSupport() {
    }

    public static boolean supports(AiProvider provider, String model) {
        if (provider == null || model == null || model.isBlank()) {
            return false;
        }
        String name = model.strip().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case OPENAI -> startsWithAny(name, OPENAI_PREFIXES) && !startsWithAny(name, OPENAI_EXCLUDED_PREFIXES);
            case CLAUDE -> name.startsWith("claude-") && !startsWithAny(name, CLAUDE_EXCLUDED_PREFIXES);
            case OLLAMA -> containsAny(name, OLLAMA_SUBSTRINGS) && !containsAny(name, OLLAMA_EXCLUDED_SUBSTRINGS);
        };
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        return prefixes.stream().anyMatch(name::startsWith);
    }

    private static boolean containsAny(String name, List<String> parts) {
        return parts.stream().anyMatch(name::contains);
    }
}
