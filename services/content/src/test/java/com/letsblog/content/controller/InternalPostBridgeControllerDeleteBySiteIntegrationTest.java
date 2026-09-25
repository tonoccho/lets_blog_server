package com.letsblog.content.controller;

import com.letsblog.content.domain.Post;
import com.letsblog.content.repository.PostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #641: InternalPostBridgeController#deleteBySiteは、legacy-api側の
 * WordPressSiteProvisioningService#deleteSiteからサイト削除時に呼ばれ、postsテーブルの物理削除を
 * 行う({@code postRepository.deleteBySiteId(siteId)}、コントローラメソッドに{@code @Transactional}）。
 *
 * <p>WHERE句の実装ミスがあると他サイトの投稿まで巻き込んで削除する重大なデータ損失につながるため、
 * モックではなく実DBに対して検証する(ADR-0006: Testcontainersではなく実MySQLへ接続する方式。
 * {@code src/test/resources/application-test.yml}が指すlbs_content_testスキーマを使う。
 * legacy-apiのMigrationTestBase/AuthorizationMatrixIntegrationTestと同じ方式)。
 *
 * <p>Spring管理のBeanとして{@link InternalPostBridgeController}を{@code @Autowired}する
 * (手動で{@code new}すると、メソッドの{@code @Transactional}がAOPプロキシ経由で有効化されず
 * {@code TransactionRequiredException}になるため、実際のリクエスト経路と同じくSpringに
 * プロキシさせたBeanを使う必要がある)。
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class InternalPostBridgeControllerDeleteBySiteIntegrationTest {

    @Autowired
    private InternalPostBridgeController controller;

    @Autowired
    private PostRepository postRepository;

    @AfterEach
    void cleanUp() {
        postRepository.deleteAll();
    }

    private Post persistPost(Long siteId, String wpPostId) {
        Post post = new Post();
        post.setSiteId(siteId);
        post.setWpPostId(wpPostId);
        post.setSlug("slug-" + siteId + "-" + wpPostId);
        post.setStatus("publish");
        return postRepository.save(post);
    }

    @Test
    void deleteBySite_対象サイトの投稿のみ削除し他サイトの投稿は残る() {
        Post targetSitePost1 = persistPost(100L, "1");
        Post targetSitePost2 = persistPost(100L, "2");
        Post otherSitePost = persistPost(200L, "1");

        ResponseEntity<Void> response = controller.deleteBySite(100L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(postRepository.findById(targetSitePost1.getId())).isEmpty();
        assertThat(postRepository.findById(targetSitePost2.getId())).isEmpty();
        assertThat(postRepository.findById(otherSitePost.getId())).isPresent();

        List<Post> remaining = postRepository.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getSiteId()).isEqualTo(200L);
    }

    @Test
    void deleteBySite_対象サイトの投稿が無い場合は他サイトへ影響せず204を返す() {
        Post otherSitePost = persistPost(200L, "1");

        ResponseEntity<Void> response = controller.deleteBySite(999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(postRepository.findById(otherSitePost.getId())).isPresent();
    }

    @Test
    void deleteBySite_同一サイトの複数投稿を1回の呼び出しで全て削除する() {
        persistPost(300L, "1");
        persistPost(300L, "2");
        persistPost(300L, "3");

        controller.deleteBySite(300L);

        assertThat(postRepository.findAll()).isEmpty();
    }
}
