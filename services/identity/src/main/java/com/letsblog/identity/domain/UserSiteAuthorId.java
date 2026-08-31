package com.letsblog.identity.domain;

import java.io.Serializable;
import java.util.Objects;

public class UserSiteAuthorId implements Serializable {

    private Long userId;
    private Long siteId;

    public UserSiteAuthorId() {
    }

    public UserSiteAuthorId(Long userId, Long siteId) {
        this.userId = userId;
        this.siteId = siteId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserSiteAuthorId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(siteId, that.siteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, siteId);
    }
}
