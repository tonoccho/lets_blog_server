package com.letsblog.api.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクトの基本情報。Project本体の所有権はproject-serviceへ移った(issue #577 stage2)。
 * legacy-apiに残るドメイン(一括管理/環境間比較・投稿publish/delete・記事プレビュー・GA/AdSenseレポート・
 * 画像生成AI選択等、いずれもCMSアダプタ・SSH実行・分析/AI資格情報ドメインへの深い依存のため#577では
 * 移設せずlegacy-apiに残る)は、このクラスをproject-serviceからの内部ブリッジ応答(ProjectServiceClient)
 * を保持する単なる転送用オブジェクトとして引き続き使う。ローカルDBのprojectsテーブルへは、もう
 * 直接永続化しない(#577 stage3でJPAマッピング・ProjectRepositoryを削除した)。GitHubトークン
 * (旧githubTokenEncrypted)は{@code ProjectApiKeyService}が{@code ProjectServiceClient.GithubTokenBridge}
 * 経由で個別に読み書きするため、このクラスでは保持しない。
 */
@Getter
@Setter
@NoArgsConstructor
public class Project {

    private Long id;
    private String name;
    private String slug;
    private Long localSiteId;
    private Long testSiteId;
    private Long productionSiteId;
    private String masterEnvironment = "test";
    private String githubRepository;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isGithubRepositoryConfigured() {
        return githubRepository != null && !githubRepository.isBlank();
    }
}
