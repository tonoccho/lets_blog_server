package com.letsblog.content.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.content.service.IdentityServiceUnavailableException;
import com.letsblog.content.testsupport.FakeHttpServer;
import com.letsblog.content.testsupport.FakeHttpServer.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * project-service への内部呼び出しの分岐(応答の本文が空のとき・失敗したとき・Authorization を付けるかどうか)。
 * クライアントは実物で、project-service は HTTP のサーバー側フェイクにする。
 */
class ProjectBridgeClientBranchTest {

    private FakeHttpServer http;
    private ProjectBridgeClient client;

    @BeforeEach
    void setUp() {
        http = new FakeHttpServer();
        client = new ProjectBridgeClient(RestClient.builder(), http.baseUrl());
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void タグデザインの応答が空ならIllegalStateException() {
        http.on("GET", "/api/internal/project/tag-design/TOC", Response.empty(200));

        assertThatThrownBy(() -> client.resolveTagDesign(1L, "TOC", "Bearer t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    void タグデザインを取得しprojectIdを問い合わせに載せる() {
        http.json("GET", "/api/internal/project/tag-design/BLOGCARD",
                "{\"backgroundColor\":\"#fff\",\"textColor\":\"#000\",\"accentColor\":\"#f00\","
                        + "\"customCss\":\"c\",\"htmlTemplate\":\"<b/>\"}");

        var design = client.resolveTagDesign(3L, "BLOGCARD", "Bearer t");

        assertThat(design.htmlTemplate()).isEqualTo("<b/>");
        assertThat(client.toColors(design).customCss()).isEqualTo("c");
        assertThat(http.last().query()).isEqualTo("projectId=3");
    }

    @Test
    void タグデザインの取得に失敗したら連携不可の例外にする() {
        http.status("GET", "/api/internal/project/tag-design/TOC", 500, "{}");

        assertThatThrownBy(() -> client.resolveTagDesign(1L, "TOC", "Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class)
                .hasMessageContaining("タグデザイン");
    }

    @Test
    void プロジェクト照会の応答が空ならslugはnull() {
        http.on("GET", "/api/internal/project/projects/5", Response.empty(200));

        assertThat(client.resolveProjectSlug(5L, "Bearer t")).isNull();
    }

    @Test
    void プロジェクトのslugを返し_失敗したら連携不可の例外にする() {
        http.json("GET", "/api/internal/project/projects/5", "{\"id\":5,\"name\":\"n\",\"slug\":\"my-slug\"}");
        assertThat(client.resolveProjectSlug(5L, "Bearer t")).isEqualTo("my-slug");

        http.status("GET", "/api/internal/project/projects/5", 503, "{}");
        assertThatThrownBy(() -> client.resolveProjectSlug(5L, "Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }

    @Test
    void siteKeyの応答が空なら未登録としてnull() {
        http.on("GET", "/api/internal/project/sites/by-key/none", Response.empty(200));

        assertThat(client.resolveSiteIdByKey("none", "Bearer t")).isNull();
    }

    @Test
    void siteKeyからサイトIDを返し_失敗したら連携不可の例外にする() {
        http.json("GET", "/api/internal/project/sites/by-key/blog", "{\"id\":9,\"siteKey\":\"blog\",\"name\":\"B\"}");
        assertThat(client.resolveSiteIdByKey("blog", "Bearer t")).isEqualTo(9L);

        http.status("GET", "/api/internal/project/sites/by-key/blog", 500, "{}");
        assertThatThrownBy(() -> client.resolveSiteIdByKey("blog", "Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }

    @Test
    void サイト一覧の応答が空なら空のリスト() {
        http.on("GET", "/api/internal/project/sites", Response.empty(200));

        assertThat(client.listSites("Bearer t")).isEmpty();
    }

    @Test
    void サイト一覧を返し_失敗したら連携不可の例外にする() {
        http.json("GET", "/api/internal/project/sites", "[{\"id\":1,\"siteKey\":\"a\",\"name\":\"A\"}]");
        assertThat(client.listSites("Bearer t")).extracting(ProjectBridgeClient.SiteSummary::siteKey)
                .containsExactly("a");

        http.status("GET", "/api/internal/project/sites", 502, "{}");
        assertThatThrownBy(() -> client.listSites("Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }

    @Test
    void アクセス可能なサイトIDの応答が空なら空のリスト() {
        http.on("GET", "/api/internal/project/users/4/site-ids", Response.empty(200));

        assertThat(client.accessibleSiteIds(4L, "Bearer t")).isEmpty();
    }

    @Test
    void アクセス可能なサイトIDを返し_失敗したら連携不可の例外にする() {
        http.json("GET", "/api/internal/project/users/4/site-ids", "[1,2]");
        assertThat(client.accessibleSiteIds(4L, "Bearer t")).containsExactly(1L, 2L);

        http.status("GET", "/api/internal/project/users/4/site-ids", 500, "{}");
        assertThatThrownBy(() -> client.accessibleSiteIds(4L, "Bearer t"))
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void トークンがnullか空白ならAuthorizationを付けない(String token) {
        http.json("GET", "/api/internal/project/sites", "[]");

        client.listSites(token);

        assertThat(http.last().header("Authorization")).isNull();
    }

    @Test
    void トークンがあればそのままAuthorizationへ転送する() {
        http.json("GET", "/api/internal/project/sites", "[]");

        client.listSites("Bearer abc");

        assertThat(http.last().header("Authorization")).isEqualTo("Bearer abc");
    }
}
