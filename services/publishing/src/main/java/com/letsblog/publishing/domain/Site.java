package com.letsblog.publishing.domain;

import com.letsblog.publishing.cms.CmsType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * サイトの基本情報(認証情報は含まない)。Site本体の所有権はproject-serviceにある(issue #577
 * stage2)。legacy-apiの{@code com.letsblog.api.domain.Site}をpublishing-serviceへ移設したもの
 * (issue #708、Epic #551 C6-2)。本クラスは、project-serviceからの内部ブリッジ応答
 * ({@link com.letsblog.publishing.client.ProjectServiceClient}）を保持する単なる転送用オブジェクトで、
 * 自身では永続化しない({@link com.letsblog.publishing.service.SiteService}参照)。
 */
@Getter
@Setter
@NoArgsConstructor
public class Site {

    private Long id;
    private String name;
    private String siteKey;
    private CmsType cmsType;
    private String baseUrl;
    private boolean managedWordpress = false;
    private String wpSlug;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
