package com.letsblog.project;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * project-service(issue #577 stage 1)。名前をつけて保存・管理するSSH鍵ペア(Ed25519)、
 * [toc]/[blogcard]/[amazon]組み込みタグのデザインカスタマイズ(プロジェクト単位)をlegacy-apiから
 * 抽出したもの。lbs_projectスキーマ(ADR-0004)を所有する。
 *
 * <p>より大きなProject/Site(CMS/WordPress自動プロビジョニング)ドメインは後続stageの対象で、
 * このstageではまだ実装しない(DBスキーマのみ先行して用意している。content-service(#576)のV1と
 * 同じ前例)。
 *
 * <p>scanBasePackagesにcom.letsblog.commonを含めるのは、ai-service(#574)と同じ理由で
 * CredentialCipher(ssh_key_pairs.private_key_encryptedの暗号化に使う、唯一のSpring Bean)を
 * 使うため。
 */
@SpringBootApplication(scanBasePackages = {"com.letsblog.project", "com.letsblog.common"})
public class ProjectServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectServiceApplication.class, args);
    }
}
