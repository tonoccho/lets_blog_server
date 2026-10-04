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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参照画像ID・denoise(変化の強さ、0〜1)の検証(issue #1601)。どちらも省略できる。 */
@DisplayName("media-service: 参照画像付き画像生成リクエストの検証(issue #1601)")
class AiImageRequestReferenceTest {

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

    private static AiImageRequest request(Long referenceImageId, Double denoise) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, null, null, null, null, null, null, null, null,
                1L, referenceImageId, denoise);
    }

    private static Set<String> violated(AiImageRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void 参照画像もdenoiseも省略できる() {
        assertEquals(Set.of(), violated(request(null, null)));
    }

    @Test
    void denoiseは0から1まで受理する() {
        assertEquals(Set.of(), violated(request(5L, 0.0)));
        assertEquals(Set.of(), violated(request(5L, 1.0)));
        assertEquals(Set.of(), violated(request(5L, 0.6)));
    }

    @Test
    void denoiseが1を超えるなら拒否する() {
        assertTrue(violated(request(5L, 1.01)).contains("denoise"));
    }

    @Test
    void denoiseが負なら拒否する() {
        assertTrue(violated(request(5L, -0.01)).contains("denoise"));
    }

    @Test
    void 既存の15引数コンストラクタは参照画像なしの要求になる() {
        AiImageRequest legacy = new AiImageRequest(
                "a cat", null, null, null, null, null, null, null, null, null, null, null, null, null, 1L);

        assertNull(legacy.referenceImageId());
        assertNull(legacy.denoise());
    }

    @Test
    void withDefaultsは参照画像を持たない() {
        AiImageRequest request = AiImageRequest.withDefaults("a cat");

        assertNull(request.referenceImageId());
        assertNull(request.denoise());
    }
}
