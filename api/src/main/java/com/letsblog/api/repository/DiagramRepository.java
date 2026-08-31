package com.letsblog.api.repository;

import com.letsblog.api.domain.Diagram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiagramRepository extends JpaRepository<Diagram, Long> {
    List<Diagram> findAllByOrderByCreatedAtDesc();

    List<Diagram> findAllByProjectIdOrderByCreatedAtDesc(Long projectId);
}
