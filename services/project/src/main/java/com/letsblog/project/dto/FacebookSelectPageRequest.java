package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

/** 投稿先の Facebook ページの選択(issue #1580)。認可の state と、選んだページの ID。 */
public record FacebookSelectPageRequest(@NotBlank String state, @NotBlank String pageId) {
}
