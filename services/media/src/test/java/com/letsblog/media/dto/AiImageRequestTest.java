package com.letsblog.media.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成リクエストのBean Validation(issue #1102)。
 *
 * <p>{@code batchSize}の上限は4から16へ上がり、{@code batchCount}(既定1)が加わった。
 * <b>合計枚数(batchSize × batchCount)の上限は設けない</b>ので、16×16=256枚の要求は
 * 妥当なリクエストとして通らなければならない。
 *
 * <p>幅・高さの「8の倍数」制約({@code @AssertTrue})は#1102以前からの仕様で、
 * 枚数の変更で壊れていないことを併せて見る。
 */
@DisplayName("media-service: 画像生成リクエストの検証(issue #1102)")
class AiImageRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static AiImageRequest request(Integer batchSize, Integer batchCount) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, null, null, null,
                batchSize, batchCount, null, null, null, 1L);
    }

    private static AiImageRequest requestWithSeed(Long seed) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, seed, null, null,
                null, null, null, null, null, 1L);
    }

    private static Set<String> violatedProperties(AiImageRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void batchSizeは16まで受理する() {
        assertEquals(Set.of(), violatedProperties(request(16, null)));
    }

    @Test
    void batchSizeが17なら拒否する() {
        assertTrue(violatedProperties(request(17, null)).contains("batchSize"));
    }

    @Test
    void batchSizeが0なら拒否する() {
        assertTrue(violatedProperties(request(0, null)).contains("batchSize"));
    }

    @Test
    void batchCountは16まで受理する() {
        assertEquals(Set.of(), violatedProperties(request(null, 16)));
    }

    @Test
    void batchCountが17なら拒否する() {
        assertTrue(violatedProperties(request(null, 17)).contains("batchCount"));
    }

    @Test
    void batchCountが0なら拒否する() {
        assertTrue(violatedProperties(request(null, 0)).contains("batchCount"));
    }

    @Test
    void 合計枚数に上限は無いので16かける16も受理する() {
        assertEquals(Set.of(), violatedProperties(request(16, 16)),
                "合計上限は設けない決定(#1102 Requirements 3)");
    }

    @Test
    void 枚数を省略したリクエストは妥当() {
        assertEquals(Set.of(), violatedProperties(AiImageRequest.withDefaults("a cat")));
    }

    @Test
    void promptが空なら拒否する() {
        assertTrue(violatedProperties(new AiImageRequest(
                "  ", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null)).contains("prompt"));
    }

    @Test
    void 幅と高さは8の倍数でなければ拒否する() {
        AiImageRequest notMultiple = new AiImageRequest(
                "a cat", null, null, null, null, null, null, 513, 515, null, null, null, null, null, 1L);

        Set<String> violations = violatedProperties(notMultiple);

        assertTrue(violations.contains("widthMultipleOf8"), violations.toString());
        assertTrue(violations.contains("heightMultipleOf8"), violations.toString());
    }

    @Test
    void 幅と高さが8の倍数なら受理する() {
        assertEquals(Set.of(), violatedProperties(new AiImageRequest(
                "a cat", null, null, null, null, null, null, 512, 768, null, null, null, null, null, 1L)));
    }

    // --- issue #1102 レビュー指摘: seedの上限 ---

    /**
     * {@code seed}にはComfyUIのKSamplerが扱う非負32bitの上限
     * ({@code 0xFFFFFFFF} = 4294967295)を超える値を受け付けない。
     *
     * <p>上限が無いと、batch countのリピートで{@code seed + repeatIndex}を計算するときに
     * {@code Long}があふれうる。#1102の当初実装は「あふれた値はComfyUIに拒否されるので
     * そのリピートだけが失敗する」とJavadocに書いていたが、これは検証していない主張だった。
     * 入口で弾いて、{@code SeedResolver}の値域0..0xFFFFFFFFがリピート後も保たれるようにする。
     */
    @Test
    void seedは0xFFFFFFFFまで受理する() {
        assertEquals(Set.of(), violatedProperties(requestWithSeed(4294967295L)));
    }

    @Test
    void seedが0xFFFFFFFFを超えると拒否する() {
        assertTrue(violatedProperties(requestWithSeed(4294967296L)).contains("seed"));
    }

    @Test
    void seedがLong_MAX_VALUEなら拒否する() {
        assertTrue(violatedProperties(requestWithSeed(Long.MAX_VALUE)).contains("seed"));
    }

    /** 負のseedは「未指定と同じくランダム扱い」という#1101からのふるまいを保つ。 */
    @Test
    void 負のseedはバリデーションでは拒否しない() {
        assertEquals(Set.of(), violatedProperties(requestWithSeed(-1L)));
    }
}
