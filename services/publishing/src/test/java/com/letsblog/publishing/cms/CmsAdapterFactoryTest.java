package com.letsblog.publishing.cms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CmsAdapterFactoryTest {

    @Test
    void testResolveWordPress() {
        WordPressAdapter wordPressAdapter = new WordPressAdapter(null, null, null);
        CmsAdapterFactory factory = new CmsAdapterFactory(List.of(wordPressAdapter));

        CmsAdapter resolved = factory.resolve(CmsType.WORDPRESS);

        assertNotNull(resolved);
        assertEquals(CmsType.WORDPRESS, resolved.supportedType());
    }

    @Test
    void testResolveUnsupportedType() {
        CmsAdapterFactory factory = new CmsAdapterFactory(List.of());

        // アダプタ未登録のCMS種別を要求した場合は未対応として例外を投げる
        assertThrows(CmsApiException.class, () -> factory.resolve(CmsType.WORDPRESS));
    }
}
