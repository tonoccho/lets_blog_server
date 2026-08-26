package com.letsblog.api.domain;

import com.letsblog.api.cms.CmsType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * サイトの基本情報(認証情報は含まない)。Site本体の所有権はproject-serviceへ移った(issue #577
 * stage2)。legacy-apiに残るドメイン(一括管理/環境間比較・投稿publish/delete・記事プレビュー等、
 * いずれもCMSアダプタ・SSH実行への深い依存のため#577では移設せずlegacy-apiに残る)は、このクラスを
 * project-serviceからの内部ブリッジ応答(ProjectServiceClient)を保持する単なる転送用オブジェクトとして
 * 引き続き使う。ローカルDBのsitesテーブルへは、もう永続化しない(#577 stage3でJPAマッピング・
 * SiteRepositoryを削除した。com.letsblog.api.service.SiteService参照)。
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
