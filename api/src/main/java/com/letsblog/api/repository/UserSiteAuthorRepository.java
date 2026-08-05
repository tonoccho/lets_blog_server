package com.letsblog.api.repository;

import com.letsblog.api.domain.UserSiteAuthor;
import com.letsblog.api.domain.UserSiteAuthorId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSiteAuthorRepository extends JpaRepository<UserSiteAuthor, UserSiteAuthorId> {
    Optional<UserSiteAuthor> findByUserIdAndSiteId(Long userId, Long siteId);
}
