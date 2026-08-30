package com.letsblog.project.service;

import com.letsblog.project.cms.ProvisioningResult;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * サイト登録時にCMS側へのプロビジョニング(デフォルトカテゴリ・タグ・著者の作成)を行う(issue #577 stage2、
 * legacy-apiから移設)。実際のWordPress操作は{@link CmsProvisioningBridgeClient}経由でlegacy-apiへ委ねる
 * (WordPressへの接続処理自体はまだ移設しない。PR説明を参照)。
 */
@Service
@Slf4j
public class ProvisioningService {

    private final CmsProvisioningBridgeClient bridgeClient;

    public ProvisioningService(CmsProvisioningBridgeClient bridgeClient) {
        this.bridgeClient = bridgeClient;
    }

    /**
     * @param actorEmail 著者として登録するサイト登録者のメールアドレス。null の場合は著者プロビジョニングをスキップする。
     */
    public Result provisionSite(String cmsType, Map<String, String> credentials, String actorEmail) {
        ProvisioningResult result;
        try {
            result = bridgeClient.provision(cmsType, credentials, actorEmail);
        } catch (RuntimeException e) {
            throw new ProvisioningException("サイトのプロビジョニングに失敗しました", e);
        }
        if (result.defaultCategoryId() != null) {
            log.info("Default category provisioned: {}", result.defaultCategoryId());
        } else if (result.categoryError() != null) {
            log.warn("Failed to provision default category: {}", result.categoryError());
        }
        if (result.defaultTagId() != null) {
            log.info("Default tag provisioned: {}", result.defaultTagId());
        } else if (result.tagError() != null) {
            log.warn("Failed to provision default tag: {}", result.tagError());
        }
        if (result.authorId() != null) {
            log.info("Author provisioned: {}", result.authorId());
        } else if (result.authorError() != null) {
            log.warn("Failed to provision author: {}", result.authorError());
        }
        return new Result(
                result.defaultCategoryId(), result.categoryError(),
                result.defaultTagId(), result.tagError(),
                result.authorId(), result.authorError());
    }

    public static class Result {
        public final String defaultCategoryId;
        public final String categoryError;
        public final String defaultTagId;
        public final String tagError;
        public final String authorId;
        public final String authorError;

        public Result(String defaultCategoryId, String categoryError, String defaultTagId, String tagError,
                String authorId, String authorError) {
            this.defaultCategoryId = defaultCategoryId;
            this.categoryError = categoryError;
            this.defaultTagId = defaultTagId;
            this.tagError = tagError;
            this.authorId = authorId;
            this.authorError = authorError;
        }
    }
}
