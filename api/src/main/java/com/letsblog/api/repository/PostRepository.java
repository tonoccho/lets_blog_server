package com.letsblog.api.repository;

import com.letsblog.api.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {
    Optional<Post> findBySiteIdAndWpPostId(Long siteId, Long wpPostId);
}
