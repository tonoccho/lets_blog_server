package com.letsblog.api.dto;

public record CreateDiagramRequest(
        Long projectId,
        String name,
        String xml,
        String svg
) {
}
