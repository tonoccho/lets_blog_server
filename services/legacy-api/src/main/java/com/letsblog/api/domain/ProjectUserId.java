package com.letsblog.api.domain;

import java.io.Serializable;
import java.util.Objects;

public class ProjectUserId implements Serializable {

    private Long projectId;
    private Long userId;

    public ProjectUserId() {
    }

    public ProjectUserId(Long projectId, Long userId) {
        this.projectId = projectId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectUserId that)) return false;
        return Objects.equals(projectId, that.projectId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, userId);
    }
}
