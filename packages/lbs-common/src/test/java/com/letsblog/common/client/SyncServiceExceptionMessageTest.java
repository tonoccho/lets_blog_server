package com.letsblog.common.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 例外メッセージの組み立て(#1831)。宛先URLの有無と、原因の有無・メッセージの有無。 */
class SyncServiceExceptionMessageTest {

    @Test
    void messageNamesTheBaseUrlWhenPresent() {
        SyncServiceUnavailableException e = new SyncServiceUnavailableException(
                "svc", "http://svc:8080", "GET /x", new RuntimeException("boom"));

        assertThat(e.getMessage()).isEqualTo("[svc @ http://svc:8080] GET /x: 呼び出し先へ到達できませんでした: boom");
    }

    @Test
    void messageOmitsTheBaseUrlWhenNullOrBlank() {
        assertThat(new SyncServiceUnavailableException("svc", null, "op", new RuntimeException("a")).getMessage())
                .startsWith("[svc] op:");
        assertThat(new SyncServiceUnavailableException("svc", "  ", "op", new RuntimeException("a")).getMessage())
                .startsWith("[svc] op:");
    }

    @Test
    void messageIsEmptyAfterTheColonWhenThereIsNoCause() {
        assertThat(new SyncServiceUnavailableException("svc", "u", "op", null).getMessage())
                .isEqualTo("[svc @ u] op: 呼び出し先へ到達できませんでした: ");
    }

    @Test
    void messageIsEmptyAfterTheColonWhenTheCauseHasNoMessage() {
        assertThat(new SyncServiceUnavailableException("svc", "u", "op", new RuntimeException()).getMessage())
                .isEqualTo("[svc @ u] op: 呼び出し先へ到達できませんでした: ");
    }
}
