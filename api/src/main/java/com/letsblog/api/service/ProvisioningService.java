package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ProvisioningService {

    private final CmsAdapterFactory cmsAdapterFactory;

    public ProvisioningService(CmsAdapterFactory cmsAdapterFactory) {
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    /**
     * サイト登録時にCMS側へのプロビジョニング(デフォルトカテゴリ・タグ・著者の作成)を行う。
     * カテゴリ/タグ/著者は個別にtry-catchし、部分的な失敗は許容してresultにエラーを記録するのみとする
     * (例: カテゴリ作成は成功したがタグ作成が失敗、といったケースでサイト登録自体は継続させる)。
     * CMSアダプタの解決自体に失敗するなど致命的な場合のみ ProvisioningException を投げ、
     * 呼び出し元(SiteService.register)でサイト登録全体をロールバックさせる。
     *
     * @param actorEmail 著者として登録するサイト登録者のメールアドレス。null の場合は著者プロビジョニングをスキップする。
     */
    public ProvisioningResult provisionSite(CmsType cmsType, CmsCredentials credentials, String actorEmail) {
        CmsAdapter adapter;
        try {
            adapter = cmsAdapterFactory.resolve(cmsType);
        } catch (Exception e) {
            throw new ProvisioningException("サイトのプロビジョニングに失敗しました", e);
        }

        ProvisioningResult result = new ProvisioningResult();

        try {
            result.defaultCategoryId = adapter.provisionDefaultCategory(credentials);
            log.info("Default category provisioned: {}", result.defaultCategoryId);
        } catch (Exception e) {
            log.warn("Failed to provision default category: {}", e.getMessage());
            result.categoryError = e.getMessage();
        }

        try {
            result.defaultTagId = adapter.provisionDefaultTag(credentials);
            log.info("Default tag provisioned: {}", result.defaultTagId);
        } catch (Exception e) {
            log.warn("Failed to provision default tag: {}", e.getMessage());
            result.tagError = e.getMessage();
        }

        if (actorEmail != null) {
            try {
                result.authorId = adapter.provisionAuthor(credentials, actorEmail);
                log.info("Author provisioned: {}", result.authorId);
            } catch (Exception e) {
                log.warn("Failed to provision author: {}", e.getMessage());
                result.authorError = e.getMessage();
            }
        }

        return result;
    }

    public static class ProvisioningResult {
        public String defaultCategoryId;
        public String categoryError;
        public String defaultTagId;
        public String tagError;
        public String authorId;
        public String authorError;
    }
}
