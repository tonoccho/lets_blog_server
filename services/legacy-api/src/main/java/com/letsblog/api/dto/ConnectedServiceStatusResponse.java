package com.letsblog.api.dto;

public record ConnectedServiceStatusResponse(String id, String name, Status status) {

    public enum Status {
        NORMAL,
        WARNING,
        ERROR
    }
}
