package br.com.scriptping.maps;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NetworkMapRepository extends JpaRepository<NetworkMapEntity, String> {
    @Query("SELECT m FROM NetworkMapEntity m ORDER BY m.updatedAt DESC NULLS LAST, m.id")
    List<NetworkMapEntity> findAllOrdered();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NetworkMapEntity m
        SET m.layout = :layout, m.updatedAt = :updatedAt, m.revision = m.revision + 1
        WHERE m.id = :id AND m.revision = :revision
        """)
    int updateLayout(@Param("id") String id, @Param("revision") long revision,
        @Param("layout") String layout, @Param("updatedAt") long updatedAt);
}
