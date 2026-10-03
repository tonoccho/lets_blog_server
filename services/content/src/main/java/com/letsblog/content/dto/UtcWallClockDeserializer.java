package com.letsblog.content.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * issue #1542: オフセット付きの date-time 入力を UTC の壁時計 {@link LocalDateTime} へ換算する。
 * オフセットなしは従来どおり UTC として受理する。空文字は null。
 */
public class UtcWallClockDeserializer extends StdDeserializer<LocalDateTime> {

    public UtcWallClockDeserializer() {
        super(LocalDateTime.class);
    }

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) {
        String text = parser.getString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UtcDateTimes.parseUtcWallClock(text);
        } catch (DateTimeParseException e) {
            throw context.weirdStringException(text, LocalDateTime.class, e.getMessage());
        }
    }
}
