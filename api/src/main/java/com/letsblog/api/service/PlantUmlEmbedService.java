package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.render.PlantUmlClient;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown本文中の ```plantuml フェンスコードブロックをPlantUMLサーバーでPNGにレンダリングし、
 * CMSのメディアライブラリへアップロードして画像参照に差し替える。
 */
@Service
public class PlantUmlEmbedService {

    private static final Pattern PLANTUML_BLOCK_PATTERN =
            Pattern.compile("```plantuml\\s*\\n(.*?)```", Pattern.DOTALL);

    private final PlantUmlClient plantUmlClient;
    private final CmsAdapterFactory cmsAdapterFactory;

    public PlantUmlEmbedService(PlantUmlClient plantUmlClient, CmsAdapterFactory cmsAdapterFactory) {
        this.plantUmlClient = plantUmlClient;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    public String embedDiagrams(CmsCredentials credentials, String markdown) {
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        Matcher matcher = PLANTUML_BLOCK_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        int index = 0;

        while (matcher.find()) {
            String diagramSource = matcher.group(1).trim();
            byte[] png = plantUmlClient.renderPng(wrapWithMarkers(diagramSource));
            String fileName = "plantuml-" + (++index) + ".png";
            MediaUploadResult uploaded = cmsAdapter.uploadMedia(credentials, fileName, "image/png", png);

            matcher.appendReplacement(result, Matcher.quoteReplacement("![diagram](" + uploaded.url() + ")"));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String wrapWithMarkers(String diagramSource) {
        if (diagramSource.contains("@startuml")) {
            return diagramSource;
        }
        return "@startuml\n" + diagramSource + "\n@enduml";
    }
}
