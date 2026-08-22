package com.letsblog.api.repository;

import com.letsblog.api.domain.SshKeyPair;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SshKeyPairRepository extends JpaRepository<SshKeyPair, Long> {

    boolean existsByName(String name);
}
