package com.letsblog.content.testsupport;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;

/**
 * 値の書き出し(シリアライズ)が必ず失敗する、実物の{@link ObjectMapper}(issue #1821)。
 *
 * <p>「JSON化に失敗しても空のJSON({@code "{}"})で続ける」分岐を通すためのもの。Mockitoで{@code ObjectMapper}を
 * 模してはいない。文字列・整数・真偽値の書き出しに失敗するシリアライザを登録した本物のマッパーなので、
 * これらを含むMap・レコードは必ず{@code JsonMappingException}になる。読み込み側(パース)は通常どおり動く。
 */
public final class BrokenObjectMapper {

    private BrokenObjectMapper() {
    }

    public static ObjectMapper create() {
        SimpleModule module = new SimpleModule("always-failing-writer");
        module.addSerializer(String.class, new Failing<>());
        module.addSerializer(Integer.class, new Failing<>());
        module.addSerializer(Long.class, new Failing<>());
        module.addSerializer(Boolean.class, new Failing<>());
        return new ObjectMapper().findAndRegisterModules().registerModule(module);
    }

    private static final class Failing<T> extends JsonSerializer<T> {
        @Override
        public void serialize(T value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            throw JsonMappingException.from(gen, "書き出しに失敗するテスト用のシリアライザ");
        }
    }
}
