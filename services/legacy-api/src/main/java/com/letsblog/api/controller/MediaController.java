package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsApiException;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.service.ImageResizeService;
import com.letsblog.api.service.SiteService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
public class MediaController {

    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final ImageResizeService imageResizeService;

    public MediaController(SiteService siteService, CmsAdapterFactory cmsAdapterFactory,
            ImageResizeService imageResizeService) {
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.imageResizeService = imageResizeService;
    }

    @PostMapping(value = "/api/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResult upload(@RequestParam("site") String site, @RequestPart("file") MultipartFile file) {
        try {
            CmsCredentials credentials = siteService.getCredentials(site);
            CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
            byte[] bytes = imageResizeService.stripMetadata(file.getBytes(), file.getContentType());
            return cmsAdapter.uploadMedia(
                    credentials,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    bytes);
        } catch (IOException e) {
            throw new CmsApiException("画像の読み込みに失敗しました", e);
        }
    }
}
