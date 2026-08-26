package com.letsblog.project.service;

import com.letsblog.project.cms.ProvisioningResult;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * ProvisioningServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 * 実際のCMS操作はCmsProvisioningBridgeClient経由でlegacy-apiへ委ねるため、ブリッジ呼び出しの結果を
 * そのままResultへ転写できることを中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningServiceTest {

    @Mock
    private CmsProvisioningBridgeClient bridgeClient;

    private ProvisioningService service() {
        return new ProvisioningService(bridgeClient);
    }

    @Test
    void provisionSite_ブリッジの結果をそのまま転写する() {
        when(bridgeClient.provision("WORDPRESS", Map.of("baseUrl", "https://example.com"), "actor@example.com"))
                .thenReturn(new ProvisioningResult("cat-1", null, "tag-1", null, "author-1", null));

        ProvisioningService.Result result = service().provisionSite(
                "WORDPRESS", Map.of("baseUrl", "https://example.com"), "actor@example.com");

        assertEquals("cat-1", result.defaultCategoryId);
        assertEquals("tag-1", result.defaultTagId);
        assertEquals("author-1", result.authorId);
    }

    @Test
    void provisionSite_部分的失敗はエラー情報を保持したまま返す() {
        when(bridgeClient.provision("WORDPRESS", Map.of(), null))
                .thenReturn(new ProvisioningResult(null, "カテゴリ作成失敗", "tag-1", null, null, null));

        ProvisioningService.Result result = service().provisionSite("WORDPRESS", Map.of(), null);

        assertEquals("カテゴリ作成失敗", result.categoryError);
        assertEquals("tag-1", result.defaultTagId);
    }

    @Test
    void provisionSite_ブリッジ呼び出し自体が失敗すればProvisioningException() {
        when(bridgeClient.provision("WORDPRESS", Map.of(), null)).thenThrow(new RuntimeException("接続失敗"));

        assertThrows(ProvisioningException.class, () -> service().provisionSite("WORDPRESS", Map.of(), null));
    }
}
