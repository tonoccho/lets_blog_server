package com.letsblog.ai.config;

import com.letsblog.ai.client.GithubTokenNotConfiguredException;
import com.letsblog.ai.github.GithubApiException;
import com.letsblog.ai.github.GithubAuthFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1771: GitHubトークン未設定(409)と認証失敗(502)の応答に、機械が読める
 * {@code details.reason} が載ること。ステータスと {@code error} は従来どおりであること。
 */
@DisplayName("GlobalExceptionHandler: GitHub連携の失敗原因(issue #1771)")
class GlobalExceptionHandlerGithubReasonTest {

    // 非 static の内部クラスにしてコンポーネントスキャンから外す(GlobalExceptionHandlerTypeMismatchTest と同じ)。
    @Controller
    @RequestMapping("/probe")
    @ResponseBody
    class ProbeController {
        @GetMapping("/not-configured")
        String notConfigured() {
            throw new GithubTokenNotConfiguredException("{\"error\":\"設定なし\"}", null);
        }

        @GetMapping("/auth-failed")
        String authFailed() {
            throw new GithubAuthFailedException("GitHubの認証に失敗しました。", null);
        }

        @GetMapping("/other")
        String other() {
            throw new GithubApiException("GitHub issue一覧の取得に失敗しました: 403 API rate limit exceeded");
        }

        @GetMapping("/illegal-state")
        String illegalState() {
            throw new IllegalStateException("GitHubリポジトリが紐付けられていません");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GlobalExceptionHandlerGithubReasonTest().new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("トークン未設定は409のまま、error は本文のまま、details.reason が付く")
    void tokenNotConfigured() throws Exception {
        mockMvc.perform(get("/probe/not-configured"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("{\"error\":\"設定なし\"}"))
                .andExpect(jsonPath("$.details.reason").value("GITHUB_TOKEN_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("認証失敗は502のまま、details.reason が付く")
    void authFailed() throws Exception {
        mockMvc.perform(get("/probe/auth-failed"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("GitHubの認証に失敗しました。"))
                .andExpect(jsonPath("$.details.reason").value("GITHUB_AUTH_FAILED"));
    }

    @Test
    @DisplayName("それ以外のGitHub失敗は502のまま、details を付けない")
    void otherGithubFailure() throws Exception {
        mockMvc.perform(get("/probe/other"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("GitHub issue一覧の取得に失敗しました: 403 API rate limit exceeded"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("専用でない IllegalStateException は409のまま、details を付けない")
    void plainIllegalState() throws Exception {
        mockMvc.perform(get("/probe/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").doesNotExist());
    }
}
