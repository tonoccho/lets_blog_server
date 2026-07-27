package com.letsblog.api.dto;

public record PostPublishResponse(Long wpPostId, String wpPostUrl, String status) {
}
