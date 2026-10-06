package com.letsblog.media.integration;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1493: 生成画像の入れ子フォルダ(作成・親の変更・画像の所属・フォルダ絞り込み・認可)と、
 * issue #1494: フォルダの改名・削除(削除の影響範囲・画像の未分類化)を、
 * 実DB(lbs_media_test)と実コントローラ経由で検証する。循環防止は再帰CTEに依存するため実DBが要る。
 * 外部境界(identity-service)は{@code @MockitoBean}で置き換える(ADR-0006)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: 生成画像の入れ子フォルダ(issue #1493)")
class GeneratedImageFolderIntegrationTest {

    private static final String FOLDERS = "/api/generated-images/folders";
    private static final String IMAGES = "/api/generated-images";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GeneratedImageRepository imageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @BeforeEach
    void setUp() {
        cleanUp();
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "admin"));
        when(identityClient.lookupProfile("Bearer admin-jwt")).thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        when(jwtDecoder.decode("user-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        when(identityClient.lookupProfile("Bearer user-jwt")).thenReturn(Optional.of(new ActorProfile(10L, "user")));
        when(jwtDecoder.decode("disabled-jwt")).thenReturn(JwtTestFixtures.jwt("sub-3", "user"));
        when(identityClient.lookupProfile("Bearer disabled-jwt")).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanUp() {
        imageRepository.deleteAll();
        // 自己参照FKのため、子を持たない行(葉)から順に消す。
        int deleted;
        do {
            deleted = jdbcTemplate.update("DELETE FROM generated_image_folders WHERE id NOT IN ("
                    + "SELECT parent_id FROM (SELECT parent_id FROM generated_image_folders "
                    + "WHERE parent_id IS NOT NULL) p)");
        } while (deleted > 0);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String jwt) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
    }

    private long createFolder(String name, Long parentId) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"parentId\":" + parentId + "}";
        String response = mockMvc.perform(as(post(FOLDERS), "admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(response, "$.id").longValue();
    }

    private long insertImage(String prompt, String tagsJson) {
        GeneratedImage image = new GeneratedImage();
        image.setPrompt(prompt);
        image.setFilePath("path/" + prompt + ".png");
        image.setMimeType("image/png");
        image.setTagsJson(tagsJson);
        return imageRepository.saveAndFlush(image).getId();
    }

    private ResultActions putParent(String jwt, long folderId, Long parentId) throws Exception {
        return mockMvc.perform(as(put(FOLDERS + "/" + folderId + "/parent"), jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":" + parentId + "}"));
    }

    private ResultActions putImageFolder(String jwt, long imageId, Long folderId) throws Exception {
        return mockMvc.perform(as(put(IMAGES + "/" + imageId + "/folder"), jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"folderId\":" + folderId + "}"));
    }

    private List<Long> listIds(String query) throws Exception {
        String body = mockMvc.perform(as(get(IMAGES + query), "admin-jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(body, "$[*].id").stream().map(Number::longValue).toList();
    }

    @Test
    @DisplayName("既存のフォルダを親にしてフォルダを作成でき、一覧に親子関係が現れる。応答は画像の件数や情報を含まない")
    void 親を指定してフォルダを作成すると一覧に階層が現れる() throws Exception {
        long parent = createFolder("親", null);
        long child = createFolder("子", parent);

        String body = mockMvc.perform(as(get(FOLDERS), "admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<Number>>read(body, "$[?(@.id==" + child + ")].parentId").get(0).longValue()).isEqualTo(parent);
        assertThat(JsonPath.<List<Object>>read(body, "$[?(@.id==" + parent + ")].parentId")).containsExactly((Object) null);
        assertThat(JsonPath.<List<String>>read(body, "$[?(@.id==" + child + ")].name").get(0)).isEqualTo("子");
        // 件数・画像の情報を露出させない(id・name・parentIdだけ)。
        assertThat(JsonPath.<java.util.Map<String, Object>>read(body, "$[0]").keySet())
                .containsExactlyInAnyOrder("id", "name", "parentId");
    }

    @Test
    @DisplayName("存在しないフォルダを親にした作成は404、空の名前は400")
    void 不正な作成要求は拒否される() throws Exception {
        mockMvc.perform(as(post(FOLDERS), "admin-jwt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"parentId\":999999}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post(FOLDERS), "admin-jwt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"parentId\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("自分自身を親にする要求は409で拒否され、親は変わらない")
    void 自分自身を親にできない() throws Exception {
        long folder = createFolder("A", null);

        putParent("admin-jwt", folder, folder).andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_id FROM generated_image_folders WHERE id = ?", Long.class, folder)).isNull();
    }

    @Test
    @DisplayName("自分の子孫(孫)を親にする要求は409で拒否され、循環は生じない")
    void 子孫を親にできない() throws Exception {
        long a = createFolder("A", null);
        long b = createFolder("B", a);
        long c = createFolder("C", b);

        putParent("admin-jwt", a, c).andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_id FROM generated_image_folders WHERE id = ?", Long.class, a)).isNull();
    }

    @Test
    @DisplayName("子孫でも自分でもないフォルダへは親を付け替えられ、nullで最上位へ戻せる")
    void 親を付け替えられる() throws Exception {
        long a = createFolder("A", null);
        long b = createFolder("B", null);

        putParent("admin-jwt", a, b).andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(b));
        putParent("admin-jwt", a, null).andExpect(status().isOk()).andExpect(jsonPath("$.parentId").doesNotExist());
        putParent("admin-jwt", a, 999999L).andExpect(status().isNotFound());
        putParent("admin-jwt", 999999L, b).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("画像をフォルダへ入れると所在が保たれ、未分類へ戻せる。存在しないフォルダ・画像は404")
    void 画像をフォルダへ入れて戻せる() throws Exception {
        long folder = createFolder("F", null);
        long image = insertImage("img", null);

        putImageFolder("admin-jwt", image, folder).andExpect(status().isOk()).andExpect(jsonPath("$.folderId").value(folder));
        mockMvc.perform(as(get(IMAGES + "/" + image), "admin-jwt"))
                .andExpect(jsonPath("$.folderId").value(folder));

        putImageFolder("admin-jwt", image, null).andExpect(status().isOk()).andExpect(jsonPath("$.folderId").doesNotExist());
        putImageFolder("admin-jwt", image, 999999L).andExpect(status().isNotFound());
        putImageFolder("admin-jwt", 999999L, folder).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("フォルダで絞ると、そのフォルダと子孫フォルダの画像だけが返る。未分類はどのフォルダにも属さない画像だけ")
    void フォルダで子孫を含めて絞り込める() throws Exception {
        long parent = createFolder("親", null);
        long child = createFolder("子", parent);
        long other = createFolder("別", null);
        long inParent = insertImage("in-parent", null);
        long inChild = insertImage("in-child", null);
        long inOther = insertImage("in-other", null);
        long unfiled = insertImage("unfiled", null);
        putImageFolder("admin-jwt", inParent, parent).andExpect(status().isOk());
        putImageFolder("admin-jwt", inChild, child).andExpect(status().isOk());
        putImageFolder("admin-jwt", inOther, other).andExpect(status().isOk());

        assertThat(listIds("?folderId=" + parent)).containsExactlyInAnyOrder(inParent, inChild);
        assertThat(listIds("?folderId=" + child)).containsExactly(inChild);
        assertThat(listIds("?unfiled=true")).containsExactly(unfiled);
        assertThat(listIds("")).hasSize(4);
        assertThat(listIds("?unfiled=false")).hasSize(4);
        assertThat(listIds("?folderId=999999")).isEmpty();
    }

    @Test
    @DisplayName("フォルダ絞り込みはタグ絞り込みとページングに併用できる(絞り込み後にページングする)")
    void フォルダ絞り込みはタグとページングと併用できる() throws Exception {
        long folder = createFolder("F", null);
        long a = insertImage("a", "[\"sky\"]");
        long b = insertImage("b", "[\"sky\"]");
        long c = insertImage("c", "[\"other\"]");
        long d = insertImage("d", "[\"sky\"]");
        for (long id : new long[] {a, b, c}) {
            putImageFolder("admin-jwt", id, folder).andExpect(status().isOk());
        }

        assertThat(listIds("?folderId=" + folder + "&tag=sky")).containsExactlyInAnyOrder(a, b);
        assertThat(listIds("?folderId=" + folder + "&limit=2&offset=0")).hasSize(2);
        assertThat(listIds("?folderId=" + folder + "&limit=2&offset=2")).hasSize(1);
        assertThat(listIds("?unfiled=true&tag=sky")).containsExactly(d);
    }

    @Test
    @DisplayName("folderIdとunfiledの同時指定は400")
    void フォルダと未分類の同時指定は400() throws Exception {
        long folder = createFolder("F", null);

        mockMvc.perform(as(get(IMAGES + "?folderId=" + folder + "&unfiled=true"), "admin-jwt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("admin以外はフォルダの作成・親の変更・画像の所属変更が403で拒否され、状態は変わらない。一覧の取得は成功する")
    void 変更はadminのみで閲覧は全員() throws Exception {
        long folder = createFolder("F", null);
        long other = createFolder("G", null);
        long image = insertImage("img", null);

        mockMvc.perform(as(post(FOLDERS), "user-jwt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nope\",\"parentId\":null}"))
                .andExpect(status().isForbidden());
        putParent("user-jwt", folder, other).andExpect(status().isForbidden());
        putImageFolder("user-jwt", image, folder).andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generated_image_folders", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_id FROM generated_image_folders WHERE id = ?", Long.class, folder)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM generated_images WHERE id = ?", Long.class, image)).isNull();

        mockMvc.perform(as(get(FOLDERS), "user-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("操作者を解決できない(無効化)ユーザーはフォルダ一覧も403")
    void 操作者なしはフォルダ一覧も403() throws Exception {
        mockMvc.perform(as(get(FOLDERS), "disabled-jwt")).andExpect(status().isForbidden());
    }

    private ResultActions putName(String jwt, long folderId, String name) throws Exception {
        return mockMvc.perform(as(put(FOLDERS + "/" + folderId + "/name"), jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"));
    }

    private ResultActions deleteFolder(String jwt, long folderId) throws Exception {
        return mockMvc.perform(as(delete(FOLDERS + "/" + folderId), jwt));
    }

    private ResultActions impact(String jwt, long folderId) throws Exception {
        return mockMvc.perform(as(get(FOLDERS + "/" + folderId + "/delete-impact"), jwt));
    }

    private int countFolders() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generated_image_folders", Integer.class);
    }

    @Test
    @DisplayName("改名すると名前だけが変わり、親と所属画像は変わらない。前後の空白は除き、同じ親の下の重複は検査しない")
    void 改名できる() throws Exception {
        long parent = createFolder("親", null);
        long folder = createFolder("旧名", parent);
        long sibling = createFolder("兄弟", parent);
        long image = insertImage("img", null);
        putImageFolder("admin-jwt", image, folder).andExpect(status().isOk());

        putName("admin-jwt", folder, " 新名 ").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(folder))
                .andExpect(jsonPath("$.name").value("新名"))
                .andExpect(jsonPath("$.parentId").value(parent));
        putName("admin-jwt", sibling, "新名").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM generated_images WHERE id = ?", Long.class, image)).isEqualTo(folder);
    }

    @Test
    @DisplayName("改名で空の名前は400、存在しないフォルダは404で、名前は変わらない")
    void 不正な改名は拒否される() throws Exception {
        long folder = createFolder("名前", null);

        putName("admin-jwt", folder, "  ").andExpect(status().isBadRequest());
        putName("admin-jwt", 999999L, "x").andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM generated_image_folders WHERE id = ?", String.class, folder)).isEqualTo("名前");
    }

    @Test
    @DisplayName("葉のフォルダを削除すると、フォルダは消え、中の画像は残って未分類に戻る")
    void 葉のフォルダを削除すると画像は未分類に戻る() throws Exception {
        long keep = createFolder("残す", null);
        long folder = createFolder("消す", null);
        long inFolder = insertImage("in", null);
        long inKeep = insertImage("keep", null);
        putImageFolder("admin-jwt", inFolder, folder).andExpect(status().isOk());
        putImageFolder("admin-jwt", inKeep, keep).andExpect(status().isOk());

        deleteFolder("admin-jwt", folder).andExpect(status().isNoContent());

        assertThat(countFolders()).isEqualTo(1);
        assertThat(imageRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM generated_images WHERE id = ?", Long.class, inFolder)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM generated_images WHERE id = ?", Long.class, inKeep)).isEqualTo(keep);
    }

    @Test
    @DisplayName("子孫を持つフォルダを削除すると子孫フォルダも消え、子孫の画像もすべて未分類に戻る(画像は1枚も消えない)。兄弟の枝は残る")
    void 子孫ごと削除しても画像は失われない() throws Exception {
        long a = createFolder("A", null);
        long b = createFolder("B", a);
        long c = createFolder("C", b);
        long d = createFolder("D", a);
        long sibling = createFolder("別枝", null);
        long inA = insertImage("inA", null);
        long inC = insertImage("inC", null);
        long inD = insertImage("inD", null);
        long inSibling = insertImage("inSibling", null);
        putImageFolder("admin-jwt", inA, a).andExpect(status().isOk());
        putImageFolder("admin-jwt", inC, c).andExpect(status().isOk());
        putImageFolder("admin-jwt", inD, d).andExpect(status().isOk());
        putImageFolder("admin-jwt", inSibling, sibling).andExpect(status().isOk());

        deleteFolder("admin-jwt", a).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForList("SELECT id FROM generated_image_folders", Long.class))
                .containsExactly(sibling);
        assertThat(imageRepository.count()).isEqualTo(4);
        assertThat(listIds("?unfiled=true")).containsExactlyInAnyOrder(inA, inC, inD);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM generated_images WHERE id = ?", Long.class, inSibling)).isEqualTo(sibling);
    }

    @Test
    @DisplayName("存在しないフォルダの削除は404")
    void 存在しないフォルダの削除は404() throws Exception {
        deleteFolder("admin-jwt", 999999L).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("削除の影響範囲は、子孫フォルダ数(自分を含まない)と、自分と子孫に属する画像の枚数を返す。何も変更しない")
    void 削除の影響範囲を返す() throws Exception {
        long a = createFolder("A", null);
        long b = createFolder("B", a);
        long c = createFolder("C", b);
        long other = createFolder("別", null);
        for (String prompt : new String[] {"1", "2"}) {
            putImageFolder("admin-jwt", insertImage("a" + prompt, null), a).andExpect(status().isOk());
        }
        putImageFolder("admin-jwt", insertImage("c", null), c).andExpect(status().isOk());
        putImageFolder("admin-jwt", insertImage("o", null), other).andExpect(status().isOk());

        impact("admin-jwt", a).andExpect(status().isOk())
                .andExpect(jsonPath("$.descendantFolderCount").value(2))
                .andExpect(jsonPath("$.imageCount").value(3));
        impact("admin-jwt", c).andExpect(status().isOk())
                .andExpect(jsonPath("$.descendantFolderCount").value(0))
                .andExpect(jsonPath("$.imageCount").value(1));
        impact("admin-jwt", other + 1000).andExpect(status().isNotFound());

        assertThat(countFolders()).isEqualTo(4);
    }

    @Test
    @DisplayName("admin以外はフォルダの改名・削除・影響範囲の取得が403で拒否され、状態は変わらない")
    void 改名と削除はadminのみ() throws Exception {
        long parent = createFolder("親", null);
        long folder = createFolder("子", parent);
        long image = insertImage("img", null);
        putImageFolder("admin-jwt", image, folder).andExpect(status().isOk());

        putName("user-jwt", folder, "改名").andExpect(status().isForbidden());
        deleteFolder("user-jwt", parent).andExpect(status().isForbidden());
        impact("user-jwt", parent).andExpect(status().isForbidden());
        // 認可を先に行うので、存在しないフォルダでも404ではなく403(存在を漏らさない)。
        deleteFolder("user-jwt", 999999L).andExpect(status().isForbidden());

        assertThat(countFolders()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM generated_image_folders WHERE id = ?", String.class, folder)).isEqualTo("子");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM generated_images WHERE id = ?", Long.class, image)).isEqualTo(folder);
    }
}
