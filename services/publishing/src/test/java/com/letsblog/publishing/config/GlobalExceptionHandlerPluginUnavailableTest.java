package com.letsblog.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.cms.LetsblogPluginStatus;
import com.letsblog.publishing.cms.LetsblogPluginUnavailableException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link GlobalExceptionHandler}のLetsblogPluginUnavailableExceptionの写像(issue #1619)。 */
class GlobalExceptionHandlerPluginUnavailableTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new MultipartProperties());

    @Test
    @DisplayName("未導入は409で、details.codeがLETSBLOG_PLUGIN_NOT_INSTALLEDになる")
    void notInstalled() {
        ResponseEntity<ErrorResponse> response = handler.handlePluginUnavailable(
                new LetsblogPluginUnavailableException(LetsblogPluginStatus.notInstalled()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).contains("未導入");
        assertThat(response.getBody().details()).isEqualTo(Map.of("code", "LETSBLOG_PLUGIN_NOT_INSTALLED"));
    }

    @Test
    @DisplayName("要更新は409で、details.codeがLETSBLOG_PLUGIN_NEEDS_UPDATEになる")
    void needsUpdate() {
        ResponseEntity<ErrorResponse> response = handler.handlePluginUnavailable(
                new LetsblogPluginUnavailableException(new LetsblogPluginStatus(
                        LetsblogPluginStatus.State.NEEDS_UPDATE, null, null, null)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).contains("要更新");
        assertThat(response.getBody().details()).isEqualTo(Map.of("code", "LETSBLOG_PLUGIN_NEEDS_UPDATE"));
    }
}
