package com.letsblog.api.repository;

import com.letsblog.api.domain.MailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MailTemplateRepository extends JpaRepository<MailTemplate, Long> {
    Optional<MailTemplate> findByTemplateKeyAndIsActiveTrue(String templateKey);
}
