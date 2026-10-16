package com.letsblog.project.config;

import com.letsblog.project.service.PlatformServiceUnavailableException;
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

/** issue #1858: platform-serviceに届かないときは、理由つきの503にする。502(identity)は変えない。 */
@DisplayName("GlobalExceptionHandler: platform-service不達の503(issue #1858)")
class GlobalExceptionHandlerPlatformUnavailableTest {

    @Controller
    @RequestMapping("/probe")
    @ResponseBody
    class ProbeController {
        @GetMapping("/platform")
        String platform() {
            throw new PlatformServiceUnavailableException("platform-serviceのgitlab-config呼び出しに失敗しました: refused", null);
        }

        @GetMapping("/identity")
        String identity() {
            throw new com.letsblog.project.service.IdentityServiceUnavailableException("identity down", null);
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GlobalExceptionHandlerPlatformUnavailableTest().new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("PlatformServiceUnavailableException は理由つきの503")
    void platformIs503WithReason() throws Exception {
        mockMvc.perform(get("/probe/platform"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("platform-serviceのgitlab-config呼び出しに失敗しました: refused"));
    }

    @Test
    @DisplayName("IdentityServiceUnavailableException は従来どおり502")
    void identityStays502() throws Exception {
        mockMvc.perform(get("/probe/identity"))
                .andExpect(status().isBadGateway());
    }
}
