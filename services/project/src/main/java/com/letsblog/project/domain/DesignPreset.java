package com.letsblog.project.domain;

import java.util.Arrays;

/**
 * 組み込みタグ([toc]/[blogcard]/[amazon])のデザインカスタマイズ画面で選択できるプリセット。
 * プリセットは色の初期値を提供するだけで、選択後にユーザーが個別に色を上書きできる
 * (TagDesignSettingには常に確定した3色が保存され、レンダリング時はそちらのみを参照する)。
 */
public enum DesignPreset {
    LIGHT("light", "ライト", "#ffffff", "#1a1a1a", "#2563eb"),
    DARK("dark", "ダーク", "#1f2937", "#f3f4f6", "#60a5fa"),
    VIVID("vivid", "ビビッド", "#fff7ed", "#7c2d12", "#ea580c");

    /** 新規プロジェクトでまだ何も保存されていない場合に使う既定プリセット。 */
    public static final DesignPreset DEFAULT = LIGHT;

    private final String id;
    private final String label;
    private final String backgroundColor;
    private final String textColor;
    private final String accentColor;

    DesignPreset(String id, String label, String backgroundColor, String textColor, String accentColor) {
        this.id = id;
        this.label = label;
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.accentColor = accentColor;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String backgroundColor() {
        return backgroundColor;
    }

    public String textColor() {
        return textColor;
    }

    public String accentColor() {
        return accentColor;
    }

    public static DesignPreset fromId(String id) {
        return Arrays.stream(values())
                .filter(preset -> preset.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不明なデザインプリセットです: " + id));
    }
}
