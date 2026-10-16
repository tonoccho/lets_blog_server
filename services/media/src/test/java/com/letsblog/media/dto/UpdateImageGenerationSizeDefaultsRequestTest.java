package com.letsblog.media.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** {@link UpdateImageGenerationSizeDefaultsRequest}の8の倍数検証(issue #292、#1820)。実際のBean Validationを通す。 */
class UpdateImageGenerationSizeDefaultsRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> messages(Integer width, Integer height) {
        return validator.validate(new UpdateImageGenerationSizeDefaultsRequest(width, height)).stream()
                .map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void 両方が8の倍数なら違反なし() {
        assertTrue(messages(512, 768).isEmpty());
    }

    @Test
    void 両方がnullなら違反なし() {
        assertTrue(messages(null, null).isEmpty());
    }

    @Test
    void widthが8の倍数でなければ違反() {
        assertEquals(Set.of("widthは8の倍数で指定してください"), messages(513, 512));
    }

    @Test
    void heightが8の倍数でなければ違反() {
        assertEquals(Set.of("heightは8の倍数で指定してください"), messages(512, 513));
    }

    @Test
    void 範囲外は違反() {
        assertEquals(2, validator.validate(new UpdateImageGenerationSizeDefaultsRequest(32, 4096)).size());
    }
}
