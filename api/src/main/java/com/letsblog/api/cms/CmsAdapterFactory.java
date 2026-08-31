package com.letsblog.api.cms;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CMS種別に応じた CmsAdapter 実装を解決するFactory。
 * CmsAdapter 実装Beanが複数になっても、呼び出し元は本Factory経由で解決すれば
 * NoUniqueBeanDefinitionException を回避できる。
 */
@Component
public class CmsAdapterFactory {

    private final Map<CmsType, CmsAdapter> adapters;

    public CmsAdapterFactory(List<CmsAdapter> adapterList) {
        Map<CmsType, CmsAdapter> map = new HashMap<>();
        for (CmsAdapter adapter : adapterList) {
            map.put(adapter.supportedType(), adapter);
        }
        this.adapters = map;
    }

    public CmsAdapter resolve(CmsType cmsType) {
        CmsAdapter adapter = adapters.get(cmsType);
        if (adapter == null) {
            throw new CmsApiException("未対応のCMS種別です: " + cmsType);
        }
        return adapter;
    }
}
