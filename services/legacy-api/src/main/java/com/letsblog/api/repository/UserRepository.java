package com.letsblog.api.repository;

import com.letsblog.api.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** JWTベースのactor解決(#563)。KeycloakのsubからローカルUserを引き当てる。 */
    Optional<User> findByKeycloakSub(String keycloakSub);
}
