package com.letsblog.api.cms;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CmsAdapterFactoryTest {

    @Test
    void testResolveWordPress() {
        WordPressAdapter wordPressAdapter = new WordPressAdapter(RestClient.builder(), null, null, null);
        CmsAdapterFactory factory = new CmsAdapterFactory(List.of(wordPressAdapter));

        CmsAdapter resolved = factory.resolve(CmsType.WORDPRESS);

        assertNotNull(resolved);
        assertEquals(CmsType.WORDPRESS, resolved.supportedType());
    }

    @Test
    void testResolveMicroCms() {
        WordPressAdapter wordPressAdapter = new WordPressAdapter(RestClient.builder(), null, null, null);
        MicroCmsAdapter microCmsAdapter = new MicroCmsAdapter(RestClient.builder());
        CmsAdapterFactory factory = new CmsAdapterFactory(List.of(wordPressAdapter, microCmsAdapter));

        CmsAdapter resolved = factory.resolve(CmsType.MICROCMS);

        assertNotNull(resolved);
        assertEquals(CmsType.MICROCMS, resolved.supportedType());
    }

    @Test
    void testResolveUnsupportedType() {
        WordPressAdapter wordPressAdapter = new WordPressAdapter(RestClient.builder(), null, null, null);
        CmsAdapterFactory factory = new CmsAdapterFactory(List.of(wordPressAdapter));

        // アダプタ未登録のCMS種別を要求した場合は未対応として例外を投げる
        assertThrows(CmsApiException.class, () -> factory.resolve(CmsType.MICROCMS));
    }
}
