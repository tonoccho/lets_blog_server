package com.letsblog.api.controller;

import com.letsblog.api.dto.PostPublishCommand;
import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.service.PostPublishService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostPublishService postPublishService;

    public PostController(PostPublishService postPublishService) {
        this.postPublishService = postPublishService;
    }

    /**
     * Markdown記事をWordPressへ新規投稿、または wpPostId 指定時は既存投稿を更新する。
     * VSCode拡張は本文中のローカル画像を images パートとして同梱し、
     * Markdown本文中ではそのファイル名(例: eyecatch.png)をそのまま画像参照として書く想定。
     */
    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PostPublishResponse publish(
            @RequestParam("site") String site,
            @RequestParam("title") String title,
            @RequestParam(value = "slug", required = false) String slug,
            @RequestParam(value = "status", defaultValue = "draft") String status,
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "wpPostId", required = false) Long wpPostId,
            @RequestParam("markdown") String markdown,
            @RequestParam(value = "images", required = false) List<MultipartFile> images
    ) {
        PostPublishCommand command = new PostPublishCommand(
                site, title, slug, status, categories, tags, wpPostId, markdown, images);
        return postPublishService.publish(command);
    }
}
