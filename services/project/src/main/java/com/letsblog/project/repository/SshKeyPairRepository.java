package com.letsblog.project.repository;

import com.letsblog.project.domain.SshKeyPair;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SshKeyPairRepository extends JpaRepository<SshKeyPair, Long> {

    boolean existsByName(String name);
}
