package com.letsblog.platform.domain;

/**
 * プラグインの種類(docs/plugins/CONTRACTS.md §4.1、issue #1807)。
 * {@code multiple}は、複数登録でき1件を「使用中」にする種類(LLM・画像生成)か、1件だけの種類(図のレンダリング・Penpot)か。
 */
public enum PluginKind {
    LLM("LLM", true),
    IMAGE_GENERATION("画像生成", true),
    DIAGRAM_RENDERER("図のレンダリング", false),
    PENPOT("Penpot", false);

    private final String displayName;
    private final boolean multiple;

    PluginKind(String displayName, boolean multiple) {
        this.displayName = displayName;
        this.multiple = multiple;
    }

    public String displayName() {
        return displayName;
    }

    public boolean multiple() {
        return multiple;
    }

    /** @throws IllegalArgumentException 種類が空または不明 */
    public static PluginKind parse(String value) {
        if (value != null) {
            for (PluginKind kind : values()) {
                if (kind.name().equals(value.trim())) {
                    return kind;
                }
            }
        }
        throw new IllegalArgumentException("種類は LLM / IMAGE_GENERATION / DIAGRAM_RENDERER / PENPOT のいずれかを指定してください");
    }
}
