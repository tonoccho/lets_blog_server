package com.letsblog.api.dto;

public record UpdateDiagramRequest(
        String name,
        String xml,
        String svg
) {
}
