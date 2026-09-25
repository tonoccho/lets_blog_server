package com.letsblog.media.dto;

public record CreateDiagramRequest(
        Long projectId,
        String name,
        String xml,
        String svg
) {
}
