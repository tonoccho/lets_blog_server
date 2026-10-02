package com.letsblog.platform.controller;

import com.letsblog.platform.service.SourceArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** issue #1491: Penpotプラグイン / MCPサーバーのZip配布(attachmentとして返す)。 */
@ExtendWith(MockitoExtension.class)
class SourceArchiveControllersTest {

    @Mock
    private SourceArchiveService service;

    @Test
    void penpotプラグインのZipをattachmentで返す() {
        byte[] bytes = {1, 2, 3};
        when(service.penpotPlugin()).thenReturn(
                new SourceArchiveService.SourceArchive(bytes, "letsblog-penpot-plugin.zip"));

        ResponseEntity<byte[]> response = new PenpotPluginController(service).download();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("attachment; filename=\"letsblog-penpot-plugin.zip\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(MediaType.parseMediaType("application/zip"), response.getHeaders().getContentType());
        assertArrayEquals(bytes, response.getBody());
    }

    @Test
    void mcpサーバーのZipをattachmentで返す() {
        byte[] bytes = {4, 5};
        when(service.mcpServer()).thenReturn(
                new SourceArchiveService.SourceArchive(bytes, "letsblog-mcp-server.zip"));

        ResponseEntity<byte[]> response = new McpServerController(service).download();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("attachment; filename=\"letsblog-mcp-server.zip\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(bytes, response.getBody());
    }
}
