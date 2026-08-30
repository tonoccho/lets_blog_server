package com.letsblog.publishing.dto;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public record PostPublishCommand(
        String siteKey,
        String title,
        String slug,
        String status,
        List<String> categories,
        List<String> tags,
        String wpPostId,
        String markdown,
        List<MultipartFile> images,
        String featuredImageFilename,
        List<String> imageReferences,
        /** front matterのpublish_scheduled_at(ISO 8601)。予約投稿しない場合はnull。 */
        String publishScheduledAt
) {
}
