package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.cms.CmsCredentials;
import org.springframework.stereotype.Service;

/**
 * legacy-apiの{@code ProjectUserSyncService#provisionUserOnSite}から委譲される、著者(WordPress
 * ユーザー)の作成/更新(issue #707、#575設計判断4の書き込み側)。{@code CmsAdapterFactory}/
 * {@code CmsAdapter}がpublishing-serviceへ完全移管されたため、legacy-api側からは直接呼べなくなった
 * 処理をこちらへ引き取った。
 */
@Service
public class AuthorProvisioningService {

    private final ProjectServiceClient projectServiceClient;
    private final CmsAdapterFactory cmsAdapterFactory;

    public AuthorProvisioningService(ProjectServiceClient projectServiceClient, CmsAdapterFactory cmsAdapterFactory) {
        this.projectServiceClient = projectServiceClient;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    /**
     * 指定サイトに著者を作成/更新する。返ってきたcmsAuthorIdの{@code user_site_authors}への永続化は
     * 引き続き呼び出し元(legacy-api)の責務。CMS側が著者の概念を持たない場合はnullを返す。
     *
     * @throws CmsApiException 認証情報に著者作成権限が無い、またはCMS呼び出し自体が失敗した場合
     */
    public String provisionAuthor(String siteKey, AuthorProvisioningRequest request) {
        CmsCredentials credentials = projectServiceClient.getCredentials(siteKey).toCmsCredentials();
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
        if (!adapter.hasAuthorProvisioningCapability(credentials)) {
            throw new CmsApiException(
                    "サイト '" + siteKey + "' の登録済み認証情報に、ユーザー作成に必要な管理者権限が"
                            + "ありません。サイト管理画面から認証情報を更新してください。");
        }
        return adapter.provisionAuthor(credentials, request);
    }
}
