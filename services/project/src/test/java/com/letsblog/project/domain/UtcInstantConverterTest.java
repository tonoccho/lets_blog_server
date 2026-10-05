package com.letsblog.project.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Instant を他のテーブルと同じ datetime 列(UTC)へ保存する変換(issue #1578)。 */
class UtcInstantConverterTest {

    private final UtcInstantConverter converter = new UtcInstantConverter();

    @Test
    void InstantをUTCのLocalDateTimeへ変換し_元へ戻せる() {
        Instant instant = Instant.parse("2026-10-05T12:34:56Z");

        LocalDateTime stored = converter.convertToDatabaseColumn(instant);

        assertEquals(LocalDateTime.of(2026, 10, 5, 12, 34, 56), stored);
        assertEquals(instant, converter.convertToEntityAttribute(stored));
    }

    @Test
    void nullはnullのまま() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }
}
