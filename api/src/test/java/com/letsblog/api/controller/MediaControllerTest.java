package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.service.ImageResizeService;
import com.letsblog.api.service.SiteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/media/upload がアップロード前にEXIF等のメタ情報を削除することを検証する(issue #432)。
 */
@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    private SiteService siteService;

    @Mock
    private CmsAdapterFactory cmsAdapterFactory;

    @Mock
    private CmsAdapter cmsAdapter;

    private final ImageResizeService imageResizeService = new ImageResizeService();

    private MediaController controller() {
        return new MediaController(siteService, cmsAdapterFactory, imageResizeService);
    }

    @Test
    void upload_JPEGのExifメタ情報を削除してからアップロードする() throws Exception {
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials(
                "https://example.com", "user", "app-password");
        when(siteService.getCredentials("my-site")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.uploadMedia(any(), anyString(), anyString(), any()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/media/1.jpg"));

        byte[] withExif = insertExifApp1(renderJpeg(100, 80));
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", withExif);

        controller().upload("my-site", file);

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(cmsAdapter).uploadMedia(any(), anyString(), anyString(), bytesCaptor.capture());
        assertFalse(containsApp1Marker(bytesCaptor.getValue()), "CMSへ送信されるバイト列からAPP1(Exif)セグメントが削除されていること");
    }

    private boolean containsApp1Marker(byte[] jpeg) {
        for (int i = 0; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xE1) {
                return true;
            }
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA) {
                break;
            }
        }
        return false;
    }

    private byte[] insertExifApp1(byte[] jpegBytes) {
        byte[] app1 = buildExifApp1Segment();
        byte[] out = new byte[2 + app1.length + (jpegBytes.length - 2)];
        System.arraycopy(jpegBytes, 0, out, 0, 2);
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpegBytes, 2, out, 2 + app1.length, jpegBytes.length - 2);
        return out;
    }

    private byte[] buildExifApp1Segment() {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write('M');
        tiff.write('M');
        writeInt16BE(tiff, 42);
        writeInt32BE(tiff, 8);
        writeInt16BE(tiff, 1);
        writeInt16BE(tiff, 0x0112);
        writeInt16BE(tiff, 3);
        writeInt32BE(tiff, 1);
        writeInt16BE(tiff, 1);
        writeInt16BE(tiff, 0);
        writeInt32BE(tiff, 0);
        byte[] tiffBytes = tiff.toByteArray();

        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + exifHeader.length + tiffBytes.length;

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        seg.write(0xFF);
        seg.write(0xE1);
        writeInt16BE(seg, length);
        seg.write(exifHeader, 0, exifHeader.length);
        seg.write(tiffBytes, 0, tiffBytes.length);
        return seg.toByteArray();
    }

    private void writeInt16BE(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeInt32BE(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private byte[] renderJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
