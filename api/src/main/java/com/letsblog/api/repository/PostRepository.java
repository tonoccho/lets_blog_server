package com.letsblog.api.repository;

import com.letsblog.api.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {
    Optional<Post> findBySiteIdAndWpPostId(Long siteId, String wpPostId);

    Optional<Post> findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(Long siteId, String slug);

    void deleteBySiteId(Long siteId);
}
