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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1647: {@code GET /api/generated-images}の省略可能な{@code source}(UPLOAD / AI)の契約を、
 * 実DBと実コントローラ経由で検証する。UPLOADは{@code provider='UPLOAD'}、AIは{@code UPLOAD}以外。
 * タグ・フォルダ・projectId・limit/offsetとはANDで効き、絞り込んだ後にページングする。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: 生成画像一覧の種別(source)絞り込み(issue #1647)")
class GeneratedImageListSourceFilterIntegrationTest {

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

    private Long upload1;
    private Long comfy;
    private Long upload2;
    private Long chatgpt;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        org.mockito.Mockito.when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "admin"));
        org.mockito.Mockito.when(identityClient.lookupProfile("Bearer admin-jwt"))
                .thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        // 新しい順: upload1, comfy, upload2, chatgpt
        upload1 = insert("UPLOAD", "[\"Sky\"]", 7L, base);
        comfy = insert("COMFYUI", "[\"sky\"]", 7L, base.minusMinutes(1));
        upload2 = insert("UPLOAD", "[\"other\"]", null, base.minusMinutes(2));
        chatgpt = insert("CHATGPT", "[\"SKY\"]", null, base.minusMinutes(3));
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    private Long insert(String provider, String tagsJson, Long projectId, LocalDateTime createdAt) {
        GeneratedImage image = new GeneratedImage();
        image.setPrompt("p");
        image.setFilePath("path/" + System.nanoTime() + ".png");
        image.setMimeType("image/png");
        image.setTagsJson(tagsJson);
        image.setProjectId(projectId);
        image.setProvider(provider);
        Long id = repository.saveAndFlush(image).getId();
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
    @DisplayName("source=UPLOADはアップロード画像だけを新しい順に返す")
    void uploadだけ() throws Exception {
        assertThat(ids(list("?source=UPLOAD").andExpect(status().isOk()))).containsExactly(upload1, upload2);
    }

    @Test
    @DisplayName("source=AIはUPLOAD以外(ComfyUI・ChatGPT)だけを返す")
    void aiだけ() throws Exception {
        assertThat(ids(list("?source=AI").andExpect(status().isOk())))
                .containsExactly(comfy, chatgpt);
    }

    @Test
    @DisplayName("source省略は従来どおり全件を返す")
    void 省略は全件() throws Exception {
        assertThat(ids(list("").andExpect(status().isOk())))
                .containsExactly(upload1, comfy, upload2, chatgpt);
    }

    @Test
    @DisplayName("sourceと limit/offset は、絞り込んだ後の一覧をページングする")
    void 絞り込み後にページングする() throws Exception {
        assertThat(ids(list("?source=AI&limit=1&offset=0").andExpect(status().isOk())))
                .containsExactly(comfy);
        assertThat(ids(list("?source=AI&limit=1&offset=1").andExpect(status().isOk())))
                .containsExactly(chatgpt);
        assertThat(ids(list("?source=AI&limit=1&offset=2").andExpect(status().isOk()))).isEmpty();
        assertThat(ids(list("?source=UPLOAD&limit=2&offset=1").andExpect(status().isOk())))
                .containsExactly(upload2);
    }

    @Test
    @DisplayName("sourceはtag・projectIdとANDで効く")
    void tagとprojectIdとAND() throws Exception {
        assertThat(ids(list("?source=AI&tag=sky").andExpect(status().isOk()))).containsExactly(comfy, chatgpt);
        assertThat(ids(list("?source=UPLOAD&tag=sky").andExpect(status().isOk()))).containsExactly(upload1);
        assertThat(ids(list("?source=UPLOAD&projectId=7").andExpect(status().isOk()))).containsExactly(upload1);
    }

    @Test
    @DisplayName("sourceはunfiledとANDで効く")
    void unfiledとAND() throws Exception {
        assertThat(ids(list("?source=UPLOAD&unfiled=true").andExpect(status().isOk())))
                .containsExactly(upload1, upload2);
    }

    @Test
    @DisplayName("不正な種別値(小文字・未知の値・空)は400")
    void 不正値は400() throws Exception {
        list("?source=upload").andExpect(status().isBadRequest());
        list("?source=COMFYUI").andExpect(status().isBadRequest());
        list("?source=").andExpect(status().isBadRequest());
    }
}
