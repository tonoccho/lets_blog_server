package com.letsblog.content.dto;

import com.letsblog.content.domain.PostStatus;

public record PostStatusOptionResponse(String value, String label) {
    public static PostStatusOptionResponse from(PostStatus status) {
        return new PostStatusOptionResponse(status.value(), status.label());
    }
}
