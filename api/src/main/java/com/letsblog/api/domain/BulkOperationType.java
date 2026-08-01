package com.letsblog.api.domain;

public enum BulkOperationType {
    CATEGORY("category"),
    PLUGIN("plugin"),
    THEME("theme");

    private final String wpCliAction;

    BulkOperationType(String wpCliAction) {
        this.wpCliAction = wpCliAction;
    }

    public String wpCliAction() {
        return wpCliAction;
    }
}
