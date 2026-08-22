package com.letsblog.api.dto;

import com.letsblog.api.domain.PostStatus;

public record PostStatusOptionResponse(String value, String label) {
    public static PostStatusOptionResponse from(PostStatus status) {
        return new PostStatusOptionResponse(status.value(), status.label());
    }
}
