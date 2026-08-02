package com.letsblog.api.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * このアプリのサーバーはUTCで稼働する前提(dockerコンテナにTZ指定なし=UTC)。
 * LocalDateTime(createdAt/updatedAt等)をそのままJSON出力するとタイムゾーン情報が付かず、
 * フロントエンドのnew Date(...)がタイムゾーン情報なしのISO文字列をブラウザのローカル時刻として
 * 誤解釈し、表示時刻がずれてしまう。UTCであることを"Z"で明示する。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcLocalDateTimeSerializer() {
        return builder -> builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value) + "Z");
            }
        });
    }
}
