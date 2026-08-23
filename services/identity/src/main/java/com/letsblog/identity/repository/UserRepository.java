package com.letsblog.identity.repository;

import com.letsblog.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findByKeycloakSub(String keycloakSub);

    /** 移行対象(#562の一括移行スクリプト)。まだKeycloakへ登録されていないユーザー。 */
    List<User> findByKeycloakSubIsNull();

    /** 孤児検出(#562)対象。既にKeycloakへ登録済みのユーザー。 */
    List<User> findByKeycloakSubIsNotNull();
}
