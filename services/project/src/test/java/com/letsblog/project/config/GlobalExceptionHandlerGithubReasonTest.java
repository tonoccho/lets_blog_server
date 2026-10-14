package com.letsblog.project.config;

import com.letsblog.project.service.GithubTokenNotConfiguredException;
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

/** issue #1771: github-access の「トークン未設定」の409に、機械が読める {@code details.reason} が載る。 */
@DisplayName("GlobalExceptionHandler: GitHubトークン未設定の原因(issue #1771)")
class GlobalExceptionHandlerGithubReasonTest {

    @Controller
    @RequestMapping("/probe")
    @ResponseBody
    class ProbeController {
        @GetMapping("/not-configured")
        String notConfigured() {
            throw new GithubTokenNotConfiguredException("ユーザーの GitHub トークンが設定されていません");
        }

        @GetMapping("/illegal-state")
        String illegalState() {
            throw new IllegalStateException("このプロジェクトにGitHubリポジトリが紐付けられていません。");
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
    @DisplayName("トークン未設定は409のまま、error は従来の文言、details.reason が付く")
    void tokenNotConfigured() throws Exception {
        mockMvc.perform(get("/probe/not-configured"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ユーザーの GitHub トークンが設定されていません"))
                .andExpect(jsonPath("$.details.reason").value("GITHUB_TOKEN_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("専用でない IllegalStateException は409のまま、details を付けない")
    void plainIllegalState() throws Exception {
        mockMvc.perform(get("/probe/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").doesNotExist());
    }
}
