package com.letsblog.media.dto;

import java.time.Instant;
import java.util.List;

public record GeneratedImageDetailResponse(
        Long id,
        Long projectId,
        String prompt,
        String negativePrompt,
        Integer steps,
        Double cfgScale,
        String samplerName,
        String scheduler,
        Long seed,
        Integer width,
        Integer height,
        Integer batchSize,
        /** バッチ内の位置(0起点、issue #1101)。#1101以前に生成した行はnull。 */
        Integer batchIndex,
        String checkpoint,
        String loraName,
        Double loraWeight,
        Instant createdAt,
        List<String> tags,
        String provider,
        /** 所属フォルダ(issue #1493)。nullは未分類。 */
        Long folderId,
        /** img2imgの参照元の画像ID(issue #1601)。参照画像を使っていない画像はnull。 */
        Long sourceImageId
) {
}
