package com.letsblog.media.dto;

/**
 * 生成画像フォルダ(issue #1493)。共通ツリーを全利用者に見せるため、フォルダ自体の情報
 * (id・名前・親)だけを返し、画像の件数や画像の情報は含めない(他プロジェクトの画像の存在を露出させない)。
 */
public record GeneratedImageFolderResponse(Long id, String name, Long parentId) {
}
