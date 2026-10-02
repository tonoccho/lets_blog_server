package com.letsblog.media.repository;

import com.letsblog.media.domain.GeneratedImageFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GeneratedImageFolderRepository extends JpaRepository<GeneratedImageFolder, Long> {

    List<GeneratedImageFolder> findAllByOrderByIdAsc();

    /**
     * 指定フォルダ自身と、そのすべての子孫フォルダのid(MySQL 8の再帰CTE)。指定フォルダが無ければ空。
     * {@code UNION}(重複除去)にしてあるのは、万一データに循環があっても再帰が終わるようにするため。
     */
    @Query(value = """
            WITH RECURSIVE tree (id) AS (
                SELECT id FROM generated_image_folders WHERE id = :rootId
                UNION
                SELECT f.id FROM generated_image_folders f JOIN tree t ON f.parent_id = t.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<Long> findSelfAndDescendantIds(@Param("rootId") Long rootId);
}
