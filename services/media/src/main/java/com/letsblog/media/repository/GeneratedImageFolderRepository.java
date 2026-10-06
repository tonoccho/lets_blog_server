package com.letsblog.media.repository;

import com.letsblog.media.domain.GeneratedImageFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 指定フォルダ自身と全子孫のidを、深いものから先に並べて返す(issue #1494)。親子のFKはRESTRICTなので、
     * 削除は葉から順に行う必要がある。{@link #findSelfAndDescendantIds}と同じ再帰CTEに深さを足したもの。
     */
    @Query(value = """
            WITH RECURSIVE tree (id, depth) AS (
                SELECT id, 0 FROM generated_image_folders WHERE id = :rootId
                UNION
                SELECT f.id, t.depth + 1 FROM generated_image_folders f JOIN tree t ON f.parent_id = t.id
            )
            SELECT id FROM tree GROUP BY id ORDER BY MAX(depth) DESC, id DESC
            """, nativeQuery = true)
    List<Long> findSelfAndDescendantIdsDeepestFirst(@Param("rootId") Long rootId);

    /** 1行だけ削除する。親子のFKがRESTRICTのため、呼び出し側が葉から順に呼ぶ。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM generated_image_folders WHERE id = :id", nativeQuery = true)
    int deleteRowById(@Param("id") Long id);
}
