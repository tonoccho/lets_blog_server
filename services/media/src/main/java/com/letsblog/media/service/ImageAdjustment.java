package com.letsblog.media.service;

/**
 * 画像編集の明るさ・コントラスト(issue #1656)。どちらも -100〜+100 で、0 が「変更なし」。
 *
 * <p>式はプレビュー(CSS の {@code filter: brightness(b) contrast(c)})と同じ: 係数は {@code 1 + 値/100}、
 * 明るさは {@code 値×b}、続いてコントラストは {@code (値-0.5)×c+0.5}(0〜1 に収める)。
 * この式を変えるときは web の {@code imageEdit.ts#adjustmentFilter} も合わせる。
 */
public record ImageAdjustment(int brightness, int contrast) {

    public static final int MIN = -100;
    public static final int MAX = 100;

    public boolean isIdentity() {
        return brightness == 0 && contrast == 0;
    }

    public boolean isInRange() {
        return brightness >= MIN && brightness <= MAX && contrast >= MIN && contrast <= MAX;
    }

    /** 8bit の成分 0〜255 を、調整後の 0〜255 へ写す表。 */
    int[] lookupTable() {
        double brightnessFactor = 1 + brightness / 100.0;
        double contrastFactor = 1 + contrast / 100.0;
        int[] table = new int[256];
        for (int value = 0; value < table.length; value++) {
            double brightened = Math.min(255.0, value * brightnessFactor);
            double contrasted = (brightened - 127.5) * contrastFactor + 127.5;
            table[value] = (int) Math.round(Math.max(0.0, Math.min(255.0, contrasted)));
        }
        return table;
    }
}
