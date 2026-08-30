package com.letsblog.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DownstreamHealthConfig}が定義する各サービス向けの
 * {@link ReactiveHealthIndicator}が、下流サービスの応答に応じてUP/DOWN/タイムアウトを
 * 正しく反映することを検証する(issue #643)。
 *
 * <p>既存の{@code legacyApiHealthIndicator}/{@code identityServiceHealthIndicator}と同じ
 * {@code downstreamHealthIndicator}ヘルパー(5秒タイムアウト、例外時DOWN)を共有しているため、
 * 各サービス固有のBean定義メソッドがこのヘルパーへ正しく配線されていることを、
 * サービスごとにUP/DOWN/タイムアウトの3パターンで確認する。
 *
 * <p>issue #743でproject/publishing/platformを追加した。網羅性そのもの
 * (services/配下の全サービスにBeanがあること)は{@link DownstreamHealthConfigContractTest}が見る。
 */
class DownstreamHealthConfigTest {

    private final DownstreamHealthConfig config = new DownstreamHealthConfig();

    private WebClient webClientWithExchange(ExchangeFunction exchangeFunction) {
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private ExchangeFunction alwaysRespond(HttpStatus status) {
        return request -> Mono.just(ClientResponse.create(status).build());
    }

    private ExchangeFunction neverRespond() {
        return request -> Mono.never();
    }

    private void assertUp(ReactiveHealthIndicator indicator) {
        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertEquals(Status.UP, health.getStatus());
                    assertEquals(200, health.getDetails().get("statusCode"));
                })
                .verifyComplete();
    }

    private void assertDown(ReactiveHealthIndicator indicator) {
        StepVerifier.create(indicator.health())
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- content ---

    @Test
    void content向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.contentServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://content:8080");
        assertUp(indicator);
    }

    @Test
    void content向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.contentServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://content:8080");
        assertDown(indicator);
    }

    @Test
    void content向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.contentServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://content:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- media ---

    @Test
    void media向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.mediaServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://media:8080");
        assertUp(indicator);
    }

    @Test
    void media向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.mediaServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://media:8080");
        assertDown(indicator);
    }

    @Test
    void media向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.mediaServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://media:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- ai ---

    @Test
    void ai向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.aiServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://ai:8080");
        assertUp(indicator);
    }

    @Test
    void ai向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.aiServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://ai:8080");
        assertDown(indicator);
    }

    @Test
    void ai向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.aiServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://ai:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- analytics ---

    @Test
    void analytics向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.analyticsServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://analytics:8080");
        assertUp(indicator);
    }

    @Test
    void analytics向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.analyticsServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://analytics:8080");
        assertDown(indicator);
    }

    @Test
    void analytics向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.analyticsServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://analytics:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- log-writer ---

    @Test
    void logWriter向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.logWriterServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://log-writer:8080");
        assertUp(indicator);
    }

    @Test
    void logWriter向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.logWriterServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://log-writer:8080");
        assertDown(indicator);
    }

    @Test
    void logWriter向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.logWriterServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://log-writer:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- project (issue #743) ---

    @Test
    void project向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.projectServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://project:8080");
        assertUp(indicator);
    }

    @Test
    void project向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.projectServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://project:8080");
        assertDown(indicator);
    }

    @Test
    void project向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.projectServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://project:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- publishing (issue #743) ---

    @Test
    void publishing向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.publishingServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://publishing:8080");
        assertUp(indicator);
    }

    @Test
    void publishing向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.publishingServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://publishing:8080");
        assertDown(indicator);
    }

    @Test
    void publishing向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.publishingServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://publishing:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }

    // --- platform (issue #743) ---

    @Test
    void platform向けインジケータは200応答でUPになる() {
        ReactiveHealthIndicator indicator = config.platformServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.OK)), "http://platform:8080");
        assertUp(indicator);
    }

    @Test
    void platform向けインジケータはエラー応答でDOWNになる() {
        ReactiveHealthIndicator indicator = config.platformServiceHealthIndicator(
                webClientWithExchange(alwaysRespond(HttpStatus.SERVICE_UNAVAILABLE)), "http://platform:8080");
        assertDown(indicator);
    }

    @Test
    void platform向けインジケータは5秒応答が無ければタイムアウトしてDOWNになる() {
        ReactiveHealthIndicator indicator = config.platformServiceHealthIndicator(
                webClientWithExchange(neverRespond()), "http://platform:8080");
        StepVerifier.withVirtualTime(indicator::health)
                .thenAwait(Duration.ofSeconds(5))
                .assertNext(health -> assertEquals(Status.DOWN, health.getStatus()))
                .verifyComplete();
    }
}
