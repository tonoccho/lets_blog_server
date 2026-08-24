package com.letsblog.api.controller;

import com.letsblog.api.dto.PostPublishCommand;
import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.service.PostDeleteService;
import com.letsblog.api.service.PostPublishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * legacy-apiのPostControllerのうち、公開(publish)・削除(delete)のみを残す(issue #576)。参照系
 * (list/lookupBySlug)はpostsテーブルの所有権とともにcontent-serviceへ移設した。publish/deleteは
 * CmsAdapter(project-service/publishing-serviceがまだ抽出されていないCMSドメイン)への深い依存が
 * あり、issue #575(publishing-service)の対象のためlegacy-apiに残っている。
 * PostPublishService/PostDeleteServiceは、postsテーブルの読み書きをcontent-serviceへの内部ブリッジ
 * (ContentServiceClient)経由で行うよう書き換えている。
 */
@Slf4j
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostPublishService postPublishService;
    private final PostDeleteService postDeleteService;

    public PostController(PostPublishService postPublishService, PostDeleteService postDeleteService) {
        this.postPublishService = postPublishService;
        this.postDeleteService = postDeleteService;
    }

    /**
     * Markdown記事をWordPressへ新規投稿、または wpPostId 指定時は既存投稿を更新する。
     * VSCode拡張は本文中のローカル画像を images パートとして同梱する。Markdown本文中の画像参照
     * (例: "assets/eyecatch.png")はマルチパートのfilenameヘッダ経由では正しく往復しない
     * (サーバー/コンテナ側でパス区切りを含むfilenameがベース名のみに変換されてしまう場合があるため)、
     * imageReferences に images と同じ順序でMarkdown中の元の参照文字列を明示的に渡す。
     */
    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PostPublishResponse publish(
            @RequestParam("site") String site,
            @RequestParam("title") String title,
            @RequestParam(value = "slug", required = false) String slug,
            @RequestParam(value = "status", defaultValue = "draft") String status,
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "wpPostId", required = false) String wpPostId,
            @RequestParam("markdown") String markdown,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "featuredImageFilename", required = false) String featuredImageFilename,
            @RequestParam(value = "imageReferences", required = false) List<String> imageReferences,
            @RequestParam(value = "publishScheduledAt", required = false) String publishScheduledAt
    ) {
        PostPublishCommand command = new PostPublishCommand(
                site, title, slug, status, categories, tags, wpPostId, markdown, images, featuredImageFilename,
                imageReferences, publishScheduledAt);
        return postPublishService.publish(command);
    }

    /**
     * 投稿を削除する(WordPressの場合、既定でゴミ箱へ移動する。完全削除は行わない)。
     */
    @DeleteMapping("/{site}/{wpPostId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String site, @PathVariable String wpPostId) {
        postDeleteService.delete(site, wpPostId);
    }
}
