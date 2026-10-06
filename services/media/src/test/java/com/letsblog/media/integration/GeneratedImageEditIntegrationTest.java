package com.letsblog.media.integration;

import com.jayway.jsonpath.JsonPath;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.client.IdentityBridgeClient;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * issue #1655: 画像の編集({@code POST /api/generated-images/{id}/edit})を実DB・実コントローラ経由で検証する。
 * 新しい画像として登録され、元の画像は変わらず、タグ・フォルダ・provider を引き継ぎ、認可が効くことを確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: 画像の編集(issue #1655)")
class GeneratedImageEditIntegrationTest {

    private static final long PROJECT_ID = 165501L;
    private static final long OTHER_PROJECT_ID = 165502L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private GeneratedImageRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Value("${app.generated-images-storage-path}")
    private String storagePath;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        org.mockito.Mockito.when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "admin"));
        org.mockito.Mockito.when(identityClient.lookupProfile("Bearer admin-jwt"))
                .thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        org.mockito.Mockito.when(jwtDecoder.decode("member-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        org.mockito.Mockito.when(identityClient.lookupProfile("Bearer member-jwt"))
                .thenReturn(Optional.of(new ActorProfile(2L, "user")));
        org.mockito.Mockito.when(identityBridgeClient.isProjectMember(PROJECT_ID, 2L, "Bearer member-jwt"))
                .thenReturn(true);
        org.mockito.Mockito.when(identityBridgeClient.isProjectMember(OTHER_PROJECT_ID, 2L, "Bearer member-jwt"))
                .thenReturn(false);
    }

    @AfterEach
    void cleanUp() throws Exception {
        repository.deleteAll();
        jdbcTemplate.update("DELETE FROM generated_image_folders");
        FileSystemUtils.deleteRecursively(Path.of(storagePath, String.valueOf(PROJECT_ID)));
        FileSystemUtils.deleteRecursively(Path.of(storagePath, String.valueOf(OTHER_PROJECT_ID)));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private long uploadSource(long projectId, byte[] bytes, String filename, String type) throws Exception {
        MvcResult created = mockMvc.perform(multipart("/api/generated-images/upload")
                        .file(new MockMultipartFile("file", filename, type, bytes))
                        .param("projectId", String.valueOf(projectId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private MvcResult edit(String token, long id, String json) throws Exception {
        return mockMvc.perform(as(post("/api/generated-images/" + id + "/edit"), token)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private BufferedImage file(long id) throws Exception {
        byte[] bytes = mockMvc.perform(as(get("/api/generated-images/" + id + "/file"), "admin-jwt"))
                .andReturn().getResponse().getContentAsByteArray();
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private long storedFileCount(long projectId) throws Exception {
        Path dir = Path.of(storagePath, String.valueOf(projectId));
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    @DisplayName("回転と切り抜きで新しい画像ができ、画素数は操作の結果と一致し、元の画像は変わらない")
    void 回転と切り抜きで新しい画像になる() throws Exception {
        long sourceId = uploadSource(PROJECT_ID,
                UploadImageFixtures.png(UploadImageFixtures.leftRedRightBlue(400, 200)), "a.png", "image/png");

        MvcResult result = edit("member-jwt", sourceId,
                "{\"operations\":[\"ROTATE_CW\"],\"crop\":{\"x\":10,\"y\":20,\"width\":100,\"height\":150}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        long newId = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(newId).isNotEqualTo(sourceId);
        assertThat(JsonPath.<Integer>read(body, "$.width")).isEqualTo(100);
        assertThat(JsonPath.<Integer>read(body, "$.height")).isEqualTo(150);
        assertThat(JsonPath.<Object>read(body, "$.sourceImageId")).isNull();
        BufferedImage edited = file(newId);
        assertThat(edited.getWidth()).isEqualTo(100);
        assertThat(edited.getHeight()).isEqualTo(150);

        BufferedImage original = file(sourceId);
        assertThat(original.getWidth()).isEqualTo(400);
        assertThat(original.getHeight()).isEqualTo(200);
        assertThat(repository.count()).isEqualTo(2);
        assertThat(storedFileCount(PROJECT_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("新しい画像は元の画像のタグ・フォルダ・providerを引き継ぐ")
    void タグとフォルダとproviderを引き継ぐ() throws Exception {
        long sourceId = uploadSource(PROJECT_ID,
                UploadImageFixtures.png(UploadImageFixtures.solid(60, 30, Color.RED, false)), "a.png", "image/png");
        mockMvc.perform(as(put("/api/generated-images/" + sourceId + "/tags"), "admin-jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"cat\",\"sky\"]}"));
        MvcResult folder = mockMvc.perform(as(post("/api/generated-images/folders"), "admin-jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"f\",\"parentId\":null}")).andReturn();
        long folderId = ((Number) JsonPath.read(folder.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(as(put("/api/generated-images/" + sourceId + "/folder"), "admin-jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"folderId\":" + folderId + "}"));

        MvcResult result = edit("member-jwt", sourceId, "{\"operations\":[\"FLIP_HORIZONTAL\"]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.provider")).isEqualTo("UPLOAD");
        assertThat(JsonPath.<Integer>read(body, "$.folderId")).isEqualTo((int) folderId);
        assertThat(JsonPath.<java.util.List<String>>read(body, "$.tags")).containsExactly("cat", "sky");
    }

    @Test
    @DisplayName("JPEGはJPEGのまま保存される")
    void JPEGは形式を保つ() throws Exception {
        long sourceId = uploadSource(PROJECT_ID,
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(80, 40, Color.RED, false)), "a.jpg", "image/jpeg");

        MvcResult result = edit("admin-jwt", sourceId, "{\"operations\":[\"ROTATE_CCW\"]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long newId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult fileResult = mockMvc.perform(as(get("/api/generated-images/" + newId + "/file"), "admin-jwt"))
                .andReturn();
        assertThat(fileResult.getResponse().getContentType()).isEqualTo("image/jpeg");
        BufferedImage edited = ImageIO.read(new ByteArrayInputStream(fileResult.getResponse().getContentAsByteArray()));
        assertThat(edited.getWidth()).isEqualTo(40);
        assertThat(edited.getHeight()).isEqualTo(80);
    }

    @Test
    @DisplayName("プロジェクトのメンバーでない利用者は403で、何も増えない")
    void 非メンバーは403() throws Exception {
        long sourceId = uploadSource(OTHER_PROJECT_ID,
                UploadImageFixtures.png(UploadImageFixtures.solid(60, 30, Color.RED, false)), "a.png", "image/png");

        MvcResult result = edit("member-jwt", sourceId, "{\"operations\":[\"ROTATE_CW\"]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(storedFileCount(OTHER_PROJECT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("存在しない画像は404")
    void 存在しない画像は404() throws Exception {
        MvcResult result = edit("admin-jwt", 987654321L, "{\"operations\":[\"ROTATE_CW\"]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("Authorizationヘッダーが無ければ401")
    void 未認証は401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/generated-images/1/edit")
                .contentType(MediaType.APPLICATION_JSON).content("{\"operations\":[\"ROTATE_CW\"]}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("範囲外の切り抜き・未知の操作・空の編集は400で、何も増えない")
    void 不正な編集は400() throws Exception {
        long sourceId = uploadSource(PROJECT_ID,
                UploadImageFixtures.png(UploadImageFixtures.solid(60, 30, Color.RED, false)), "a.png", "image/png");

        for (String json : new String[] {
                "{\"crop\":{\"x\":50,\"y\":0,\"width\":30,\"height\":10}}",
                "{\"operations\":[\"ROTATE_45\"]}",
                "{\"operations\":[]}"}) {
            assertThat(edit("member-jwt", sourceId, json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        assertThat(repository.count()).isEqualTo(1);
        assertThat(storedFileCount(PROJECT_ID)).isEqualTo(1);
    }
}
