package com.letsblog.content.repository;

import com.letsblog.content.domain.CustomTagTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomTagTemplateRepository extends JpaRepository<CustomTagTemplate, Long> {

    @Query("SELECT t FROM CustomTagTemplate t WHERE t.projectId IS NULL AND t.isPublished = true ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> findPublishedGlobalTemplates();

    @Query("SELECT t FROM CustomTagTemplate t WHERE (t.projectId IS NULL OR t.projectId = :projectId) AND t.isPublished = true ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> findPublishedTemplatesByProject(@Param("projectId") Long projectId);

    @Query("SELECT t FROM CustomTagTemplate t WHERE (t.projectId IS NULL OR t.projectId = :projectId) ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> findAllTemplatesByProject(@Param("projectId") Long projectId);

    @Query("SELECT t FROM CustomTagTemplate t WHERE t.projectId IS NULL ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> findGlobalTemplates();

    @Query("SELECT t FROM CustomTagTemplate t WHERE t.category = :category AND t.isPublished = true ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> findByCategory(@Param("category") String category);

    @Query("SELECT t FROM CustomTagTemplate t WHERE (t.projectId IS NULL OR t.projectId = :projectId) AND t.category = :category AND t.isPublished = true ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> findByCategoryAndProject(@Param("category") String category, @Param("projectId") Long projectId);

    @Query("SELECT t FROM CustomTagTemplate t WHERE t.templateName LIKE %:keyword% AND t.isPublished = true ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> searchByKeyword(@Param("keyword") String keyword);

    @Query("SELECT t FROM CustomTagTemplate t WHERE (t.projectId IS NULL OR t.projectId = :projectId) AND t.templateName LIKE %:keyword% AND t.isPublished = true ORDER BY t.createdAt DESC")
    List<CustomTagTemplate> searchByKeywordAndProject(@Param("keyword") String keyword, @Param("projectId") Long projectId);

    List<CustomTagTemplate> findByCreatedBy(Long createdBy);

    Optional<CustomTagTemplate> findByOriginalTagId(Long originalTagId);
}
