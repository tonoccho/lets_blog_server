package com.letsblog.identity.repository;

import com.letsblog.identity.domain.UserSiteAuthor;
import com.letsblog.identity.domain.UserSiteAuthorId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSiteAuthorRepository extends JpaRepository<UserSiteAuthor, UserSiteAuthorId> {
    Optional<UserSiteAuthor> findByUserIdAndSiteId(Long userId, Long siteId);
}
