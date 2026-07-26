package io.github.viniciusssantos.accountshield.crypto.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectKeyRepository extends JpaRepository<SubjectKeyEntity, String> {

    @Query(value = """
            SELECT * FROM crypto.subject_key
            WHERE kek_version <> :activeKekVersion AND destroyed_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<SubjectKeyEntity> findBatchNeedingRewrap(
            @Param("activeKekVersion") int activeKekVersion, @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT COUNT(*) FROM crypto.subject_key
            WHERE kek_version <> :activeKekVersion AND destroyed_at IS NULL
            """, nativeQuery = true)
    long countNeedingRewrap(@Param("activeKekVersion") int activeKekVersion);
}
