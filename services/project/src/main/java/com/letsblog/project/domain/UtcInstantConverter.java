package com.letsblog.project.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * {@link Instant}を、他のテーブルと同じ{@code datetime}列(UTC)へ保存する(issue #1578)。
 * Hibernate既定のInstantの扱い(TIMESTAMP_UTC)だと、{@code ddl-auto: validate}が{@code datetime}列との
 * 型の食い違いを報告しうるため、列の型は他のテーブルに揃えたままにする。
 */
@Converter
public class UtcInstantConverter implements AttributeConverter<Instant, LocalDateTime> {

    @Override
    public LocalDateTime convertToDatabaseColumn(Instant attribute) {
        return attribute == null ? null : LocalDateTime.ofInstant(attribute, ZoneOffset.UTC);
    }

    @Override
    public Instant convertToEntityAttribute(LocalDateTime dbData) {
        return dbData == null ? null : dbData.toInstant(ZoneOffset.UTC);
    }
}
