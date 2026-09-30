package com.letsblog.media.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.repository.GeneratedImageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1472: {@code GET /api/generated-images}の省略可能な{@code limit}/{@code offset}の契約を、
 * 実DB(lbs_media_test)と実コントローラ経由で検証する。
 *
 * <p>並び順はcreatedAt降順・同時刻はid降順。tag指定時は絞り込んだ後の一覧にページングを適用する。
 * limit省略時は従来どおり全件を返す。limitの上限は100で、超過は切り詰めずに400とする
 * (切り詰めると「返った件数がlimit未満=終端」という呼び出し側の判定が誤るため)。
 * 外部境界(identity-service)は{@code @MockitoBean}で置き換える(ADR-0006)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: 生成画像一覧のlimit/offsetページング(issue #1472)")
class GeneratedImageListPagingIntegrationTest {

    private static final String PATH = "/api/generated-images";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GeneratedImageRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    /** 新しい順(createdAt降順)に並べたときのid。 */
    private List<Long> newestFirstIds;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        org.mockito.Mockito.when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "admin"));
        org.mockito.Mockito.when(identityClient.lookupProfile("Bearer admin-jwt"))
                .thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        // index 0 が最も新しい。tagsは index 0,2,4 に "Sky"、index 1 に "other"。
        newestFirstIds = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        String[] tags = {"[\"Sky\"]", "[\"other\"]", "[\"sky\",\"x\"]", "[]", "[\"SKY\"]"};
        for (int i = 0; i < 5; i++) {
            Long id = insert("p" + i, tags[i], null, base.minusMinutes(i));
            newestFirstIds.add(id);
        }
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    private Long insert(String prompt, String tagsJson, Long projectId, LocalDateTime createdAt) {
        GeneratedImage image = new GeneratedImage();
        image.setPrompt(prompt);
        image.setFilePath("path/" + prompt + ".png");
        image.setMimeType("image/png");
        image.setTagsJson(tagsJson);
        image.setProjectId(projectId);
        Long id = repository.saveAndFlush(image).getId();
        // createdAt は @PrePersist で現在時刻になり updatable=false なので、SQLで決め打ちする。
        jdbcTemplate.update("UPDATE generated_images SET created_at = ? WHERE id = ?", createdAt, id);
        return id;
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get(PATH + query).header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"));
    }

    private List<Long> ids(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<List<Number>>read(body, "$[*].id").stream()
                .map(Number::longValue).toList();
    }

    @Test
    @DisplayName("limit=2&offset=2は新しい順の3・4件目を返す")
    void limitとoffsetで新しい順の途中を返す() throws Exception {
        ResultActions result = list("?limit=2&offset=2").andExpect(status().isOk());

        assertThat(ids(result)).containsExactly(newestFirstIds.get(2), newestFirstIds.get(3));
    }

    @Test
    @DisplayName("offsetが件数以上なら空配列(200)")
    void offsetが件数以上なら空配列() throws Exception {
        list("?limit=2&offset=5").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("offset省略は0として扱い、先頭からlimit件を返す")
    void offset省略は先頭から() throws Exception {
        ResultActions result = list("?limit=2").andExpect(status().isOk());

        assertThat(ids(result)).containsExactly(newestFirstIds.get(0), newestFirstIds.get(1));
    }

    @Test
    @DisplayName("limit省略は従来どおり全件を新しい順の配列で返す(offsetだけ指定しても全件扱いではなくoffset分は飛ばす)")
    void limit省略は全件() throws Exception {
        ResultActions result = list("").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prompt").value("p0"))
                .andExpect(jsonPath("$[0].tags[0]").value("Sky"));

        assertThat(ids(result)).containsExactlyElementsOf(newestFirstIds);
    }

    @Test
    @DisplayName("limit省略でoffsetだけ指定した場合も、offset件を飛ばした残りを返す")
    void limit省略でoffsetだけ指定() throws Exception {
        ResultActions result = list("?offset=3").andExpect(status().isOk());

        assertThat(ids(result)).containsExactly(newestFirstIds.get(3), newestFirstIds.get(4));
    }

    @Test
    @DisplayName("limit=0は400")
    void limitが0は400() throws Exception {
        list("?limit=0").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("offset=-1は400")
    void offsetが負は400() throws Exception {
        list("?limit=2&offset=-1").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("limitが上限(100)を超えると切り詰めず400、ちょうど100は200")
    void limitの上限() throws Exception {
        list("?limit=101").andExpect(status().isBadRequest());
        list("?limit=100").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @DisplayName("tagとlimitの併用は、絞り込んだ後の一覧(大文字小文字無視)をページングする")
    void tag絞り込み後にページングする() throws Exception {
        // "sky" に一致するのは index 0,2,4。絞り込み後の2件目から最大2件 → index 2,4。
        ResultActions result = list("?tag=sky&limit=2&offset=1").andExpect(status().isOk());

        assertThat(ids(result)).containsExactly(newestFirstIds.get(2), newestFirstIds.get(4));
    }

    @Test
    @DisplayName("tagとlimitの併用で、絞り込み後の件数を超えるoffsetは空配列")
    void tag絞り込み後のoffset超過は空() throws Exception {
        list("?tag=sky&limit=2&offset=3").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("tagだけ(limit省略)は絞り込んだ全件を返す")
    void tagのみは絞り込み全件() throws Exception {
        ResultActions result = list("?tag=sky").andExpect(status().isOk());

        assertThat(ids(result)).containsExactly(
                newestFirstIds.get(0), newestFirstIds.get(2), newestFirstIds.get(4));
    }

    @Test
    @DisplayName("createdAtが同時刻ならid降順に並び、ページ境界で揺れない")
    void 同時刻はid降順() throws Exception {
        repository.deleteAll();
        LocalDateTime same = LocalDateTime.of(2026, 2, 1, 0, 0, 0);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            created.add(insert("t" + i, "[]", null, same));
        }

        List<Long> first = ids(list("?limit=2&offset=0").andExpect(status().isOk()));
        List<Long> second = ids(list("?limit=2&offset=2").andExpect(status().isOk()));

        assertThat(first).containsExactly(created.get(3), created.get(2));
        assertThat(second).containsExactly(created.get(1), created.get(0));
    }

    @Test
    @DisplayName("projectId指定でも、そのプロジェクトの画像だけをページングする")
    void projectId指定でページングする() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 3, 1, 0, 0, 0);
        Long a = insert("proj-a", "[]", 77L, base);
        Long b = insert("proj-b", "[]", 77L, base.minusMinutes(1));
        insert("proj-c", "[]", 77L, base.minusMinutes(2));

        ResultActions result = list("?projectId=77&limit=2&offset=0").andExpect(status().isOk());

        assertThat(ids(result)).containsExactly(a, b);
        assertThat(ids(list("?projectId=77&limit=2&offset=2"))).hasSize(1);
        assertThat(ids(list("?projectId=77"))).hasSize(3);
    }
}
