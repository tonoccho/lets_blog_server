package com.letsblog.media.integration;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1599: 画像アップロード({@code POST /api/generated-images/upload})を、実DBと実コントローラ経由で検証する。
 * prompt NULL許可のマイグレーション、元の解像度のままの登録、メタ情報除去、拒否時に何も残らないこと、認可を確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: 画像アップロード(issue #1599)")
class GeneratedImageUploadIntegrationTest {

    private static final long PROJECT_ID = 159901L;
    private static final long OTHER_PROJECT_ID = 159902L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private GeneratedImageRepository repository;
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
        FileSystemUtils.deleteRecursively(Path.of(storagePath, String.valueOf(PROJECT_ID)));
        FileSystemUtils.deleteRecursively(Path.of(storagePath, String.valueOf(OTHER_PROJECT_ID)));
    }

    private MvcResult upload(String token, long projectId, String filename, String contentType, byte[]... bytes)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, bytes[0]);
        return mockMvc.perform(multipart("/api/generated-images/upload")
                        .file(file)
                        .param("projectId", String.valueOf(projectId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
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
    @DisplayName("メンバーがJPEGをアップロードすると、UPLOADとして元の解像度で登録され、ファイルが取得できる")
    void アップロードして取得できる() throws Exception {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(4000, 3000, Color.RED, false));

        MvcResult created = upload("member-jwt", PROJECT_ID, "photo.jpg", "image/jpeg", src);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String body = created.getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.provider")).isEqualTo("UPLOAD");
        assertThat(com.jayway.jsonpath.JsonPath.<Object>read(body, "$.prompt")).isNull();

        MvcResult file = mockMvc.perform(get("/api/generated-images/" + id + "/file")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andReturn();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getResponse().getContentAsByteArray()));
        assertThat(image.getWidth()).isEqualTo(4000);
        assertThat(image.getHeight()).isEqualTo(3000);
        mockMvc.perform(get("/api/generated-images/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.width").value(4000))
                .andExpect(jsonPath("$.height").value(3000));

        mockMvc.perform(get("/api/generated-images").param("projectId", String.valueOf(PROJECT_ID))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].provider").value("UPLOAD"));
    }

    @Test
    @DisplayName("縦長PNG・小さなPNGも元の画素数・PNGのまま登録され、width/heightが一致する")
    void 縦長と小さい画像() throws Exception {
        for (BufferedImage src : new BufferedImage[] {
                UploadImageFixtures.verticalBands(700, 1400),
                UploadImageFixtures.solid(320, 180, Color.BLUE, false)}) {
            MvcResult created = upload("admin-jwt", PROJECT_ID, "a.png", "image/png", UploadImageFixtures.png(src));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            long id = ((Number) com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id"))
                    .longValue();
            MvcResult file = mockMvc.perform(get("/api/generated-images/" + id + "/file")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")).andReturn();
            assertThat(file.getResponse().getContentType()).isEqualTo("image/png");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getResponse().getContentAsByteArray()));
            assertThat(image.getWidth()).isEqualTo(src.getWidth());
            assertThat(image.getHeight()).isEqualTo(src.getHeight());
            mockMvc.perform(get("/api/generated-images/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                    .andExpect(jsonPath("$.width").value(src.getWidth()))
                    .andExpect(jsonPath("$.height").value(src.getHeight()));
        }
    }

    @Test
    @DisplayName("GPS付きJPEGをアップロードしても、保存画像にGPS/EXIFは残らない")
    void GPSは残らない() throws Exception {
        byte[] src = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false)), 1);

        MvcResult created = upload("admin-jwt", PROJECT_ID, "gps.jpg", "image/jpeg", src);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id"))
                .longValue();

        byte[] stored = mockMvc.perform(get("/api/generated-images/" + id + "/file")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(UploadImageFixtures.GPS_MARKER);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    @DisplayName("GIFは400とエラーメッセージで拒否され、行もファイルも残らない")
    void GIFは拒否され何も残らない() throws Exception {
        MvcResult result = upload("admin-jwt", PROJECT_ID, "a.gif", "image/gif", UploadImageFixtures.gif(10, 10));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("対応していない画像形式です");
        assertThat(repository.count()).isZero();
        assertThat(storedFileCount(PROJECT_ID)).isZero();
    }

    @Test
    @DisplayName("20MBを超えるファイルは拒否され、何も残らない")
    void 大きすぎるファイルは拒否され何も残らない() throws Exception {
        byte[] tooBig = new byte[20 * 1024 * 1024 + 1];
        tooBig[0] = (byte) 0xFF;
        tooBig[1] = (byte) 0xD8;

        MvcResult result = upload("admin-jwt", PROJECT_ID, "big.jpg", "image/jpeg", tooBig);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("上限(20MB)");
        assertThat(repository.count()).isZero();
        assertThat(storedFileCount(PROJECT_ID)).isZero();
    }

    @Test
    @DisplayName("プロジェクトのメンバーでない利用者は403で、何も残らない")
    void 非メンバーは403() throws Exception {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, Color.RED, false));

        MvcResult result = upload("member-jwt", OTHER_PROJECT_ID, "a.png", "image/png", src);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(repository.count()).isZero();
        assertThat(storedFileCount(OTHER_PROJECT_ID)).isZero();
    }

    @Test
    @DisplayName("Authorizationヘッダーが無ければ401")
    void 未認証は401() throws Exception {
        mockMvc.perform(multipart("/api/generated-images/upload")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}))
                        .param("projectId", String.valueOf(PROJECT_ID)))
                .andExpect(status().isUnauthorized());
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("PNG以外のContent-Typeを名乗っても、中身がJPEG/PNGなら中身で判定して受け付ける")
    void 中身で判定する() throws Exception {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, Color.RED, false));

        MvcResult result = upload("admin-jwt", PROJECT_ID, "a.bin", "application/octet-stream", src);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }
}
