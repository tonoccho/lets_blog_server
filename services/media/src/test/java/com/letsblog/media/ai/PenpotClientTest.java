package com.letsblog.media.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * PenpotClientの回帰テスト。MockRestServiceServerでPenpotのRPC API(/api/rpc/command/*)応答を模擬する。
 * ログイン成功時にセットされるauth-token Cookieの受け渡し、アカウント未作成時の自動登録フォールバック、
 * 公開URL(内部URLと別)でのリンク組み立て、コメント作成失敗時のベストエフォート動作を検証する。
 */
class PenpotClientTest {

    private static final String BASE_URL = "http://penpot.test";
    private static final String PUBLIC_URL = "http://localhost:9001";
    private static final String EMAIL = "lbs-service@letsblog.local";
    private static final String PASSWORD = "service-password";

    private MockRestServiceServer server;
    private PenpotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PenpotClient(builder, PUBLIC_URL, EMAIL, PASSWORD);
    }

    private void expectSuccessfulLogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Set-Cookie", "auth-token=session-abc; Path=/; HttpOnly");
        server.expect(requestTo(BASE_URL + "/api/rpc/command/login-with-password"))
                .andRespond(withSuccess(
                        "{\"defaultProjectId\":\"project-1\",\"defaultTeamId\":\"team-1\"}", MediaType.APPLICATION_JSON)
                        .headers(headers));
    }

    private void expectSuccessfulShareLink() {
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-share-link"))
                .andRespond(withSuccess("{\"id\":\"share-1\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void createDesignFile_ログイン済みならファイルとコメントを作成し誰でも開ける共有URLを返す() {
        expectSuccessfulLogin();
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-file"))
                .andRespond(withSuccess(
                        "{\"id\":\"file-1\",\"data\":{\"pages\":[\"page-1\"]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-comment-thread"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectSuccessfulShareLink();

        PenpotClient.DesignFile result = client.createDesignFile("カスタムタグ: my-button", "Ollamaへのプロンプト");

        assertEquals("file-1", result.fileId());
        assertEquals("project-1", result.projectId());
        assertEquals(PUBLIC_URL + "/#/view/file-1?page-id=page-1&share-id=share-1", result.url());
    }

    @Test
    void createDesignFile_アカウント未作成なら自動登録してから作成する() {
        server.expect(requestTo(BASE_URL + "/api/rpc/command/login-with-password"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"code\":\"wrong-credentials\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/rpc/command/prepare-register-profile"))
                .andRespond(withSuccess("{\"token\":\"reg-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/rpc/command/register-profile"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectSuccessfulLogin();
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-file"))
                .andRespond(withSuccess(
                        "{\"id\":\"file-1\",\"data\":{\"pages\":[\"page-1\"]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-comment-thread"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectSuccessfulShareLink();

        PenpotClient.DesignFile result = client.createDesignFile("カスタムタグ: my-button", "プロンプト");

        assertEquals("file-1", result.fileId());
    }

    @Test
    void createDesignFile_コメント作成が失敗してもファイルURLは返す() {
        expectSuccessfulLogin();
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-file"))
                .andRespond(withSuccess(
                        "{\"id\":\"file-1\",\"data\":{\"pages\":[\"page-1\"]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-comment-thread"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"code\":\"params-validation\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        expectSuccessfulShareLink();

        PenpotClient.DesignFile result = client.createDesignFile("カスタムタグ: my-button", "プロンプト");

        assertEquals(PUBLIC_URL + "/#/view/file-1?page-id=page-1&share-id=share-1", result.url());
    }

    @Test
    void createDesignFile_ファイル作成自体が失敗すればAiServiceExceptionを投げる() {
        expectSuccessfulLogin();
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-file"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{}")
                        .contentType(MediaType.APPLICATION_JSON));

        AiServiceException exception = assertThrows(AiServiceException.class,
                () -> client.createDesignFile("カスタムタグ: my-button", "プロンプト"));

        assertTrue(exception.getMessage().contains("Penpotデザインファイルの作成に失敗しました"));
    }

    @Test
    void createDesignFile_共有リンク作成が失敗すればAiServiceExceptionを投げる() {
        expectSuccessfulLogin();
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-file"))
                .andRespond(withSuccess(
                        "{\"id\":\"file-1\",\"data\":{\"pages\":[\"page-1\"]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-comment-thread"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/rpc/command/create-share-link"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{}")
                        .contentType(MediaType.APPLICATION_JSON));

        AiServiceException exception = assertThrows(AiServiceException.class,
                () -> client.createDesignFile("カスタムタグ: my-button", "プロンプト"));

        assertTrue(exception.getMessage().contains("Penpot共有リンクの作成に失敗しました"));
    }
}
