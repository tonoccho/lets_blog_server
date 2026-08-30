package com.letsblog.media.dto;

import java.util.List;

/** 生成画像のタグを手動で編集・追加するためのリクエスト(issue #281)。 */
public record UpdateGeneratedImageTagsRequest(List<String> tags) {
}
