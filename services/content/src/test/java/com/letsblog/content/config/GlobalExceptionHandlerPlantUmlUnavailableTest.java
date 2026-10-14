package com.letsblog.content.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.content.render.PlantUmlRenderRejectedException;
import com.letsblog.content.service.InvalidPlantUmlTagException;
import com.letsblog.content.service.PlantUmlServerUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** PlantUMLサーバー不通は502、タグ記法の誤りは従来どおり400(issue #1759)。 */
class GlobalExceptionHandlerPlantUmlUnavailableTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("PlantUMLサーバーに接続できないときは502でメッセージを返す")
    void unavailable() {
        ResponseEntity<ErrorResponse> response = handler.handlePlantUmlServerUnavailable(
                new PlantUmlServerUnavailableException("PlantUMLサーバーに接続できません: x", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).contains("PlantUMLサーバーに接続できません");
    }

    @Test
    @DisplayName("タグ記法の誤りは従来どおり400")
    void invalidTagStays400() {
        ResponseEntity<ErrorResponse> response =
                handler.handleInvalidPlantUmlTag(new InvalidPlantUmlTagException("記法が不正"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PlantUMLが図を拒否したときは400で同じ文言を返し、接続できない旨は含まない(issue #1776)")
    void rejectedIs400() {
        ResponseEntity<ErrorResponse> response = handler.handlePlantUmlRenderRejected(
                new PlantUmlRenderRejectedException("PlantUML が図を描けませんでした: Syntax Error?"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("PlantUML が図を描けませんでした: Syntax Error?");
        assertThat(response.getBody().error()).doesNotContain("接続できません");
    }
}
