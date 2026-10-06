package com.letsblog.media.dto;

import com.letsblog.media.service.ImageCropRegion;
import com.letsblog.media.service.ImageEditOperation;

import java.util.List;

/**
 * 画像の編集要求(issue #1655)。{@code operations}は並べた順に適用し、{@code crop}は適用後の画像の座標で指定する。
 * 操作も切り抜きも無い要求は400。
 */
public record EditGeneratedImageRequest(List<ImageEditOperation> operations, ImageCropRegion crop) {
}
