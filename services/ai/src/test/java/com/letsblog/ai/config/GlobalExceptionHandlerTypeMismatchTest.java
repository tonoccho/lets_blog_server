package com.letsblog.ai.config;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1468: @PathVariable / @RequestParam の型変換失敗(MethodArgumentTypeMismatchException)は、
 * 原因連鎖を辿って IllegalArgumentException のハンドラ(409)に一致してしまっていた。
 * 実際の Spring の例外解決(ExceptionHandlerExceptionResolver)を通して、400になること、
 * 業務ロジックが投げる IllegalArgumentException は従来どおり409であることを確認する。
 */
@DisplayName("GlobalExceptionHandler: 型変換失敗は400(issue #1468)")
class GlobalExceptionHandlerTypeMismatchTest {

    enum Kind { ALPHA, BETA }

    // Spring 6以降は @Controller が無いとハンドラとして認識されない。一方 static のネストクラスは
    // 統合テストのコンポーネントスキャンに拾われて本番のエンドポイント一覧を汚すため、
    // 非 static の内部クラスにしてスキャン対象から外す。
    @Controller
    @RequestMapping("/probe")
    @ResponseBody
    class ProbeController {
        @GetMapping("/enum/{kind}")
        String byEnum(@PathVariable Kind kind) {
            return kind.name();
        }

        @GetMapping("/number/{id}")
        String byNumber(@PathVariable Long id) {
            return String.valueOf(id);
        }

        @GetMapping("/param")
        String byParam(@RequestParam Integer limit) {
            return String.valueOf(limit);
        }

        @GetMapping("/business")
        String business() {
            throw new IllegalArgumentException("業務上の不正な値");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GlobalExceptionHandlerTypeMismatchTest().new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("enumのPathVariableに未知の値を渡すと400")
    void enumPathVariable() throws Exception {
        mockMvc.perform(get("/probe/enum/UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("パラメータ 'kind' の値が不正です。"))
                .andExpect(content().string(not(containsString("Kind"))));
    }

    @Test
    @DisplayName("数値のPathVariableに非数値を渡すと400")
    void numericPathVariable() throws Exception {
        mockMvc.perform(get("/probe/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("パラメータ 'id' の値が不正です。"));
    }

    @Test
    @DisplayName("数値のRequestParamに非数値を渡すと400")
    void numericRequestParam() throws Exception {
        mockMvc.perform(get("/probe/param").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("パラメータ 'limit' の値が不正です。"));
    }

    @Test
    @DisplayName("正当な値は200")
    void validValuesPass() throws Exception {
        mockMvc.perform(get("/probe/enum/ALPHA")).andExpect(status().isOk());
        mockMvc.perform(get("/probe/number/5")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("業務ロジックのIllegalArgumentExceptionは従来どおり409")
    void businessIllegalArgumentStays409() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(status().is(HttpStatus.CONFLICT.value()))
                .andExpect(jsonPath("$.error").value("業務上の不正な値"));
    }
}
