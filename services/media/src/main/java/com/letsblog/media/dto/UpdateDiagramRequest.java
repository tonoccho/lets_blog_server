package com.letsblog.media.dto;

public record UpdateDiagramRequest(
        String name,
        String xml,
        String svg
) {
}
