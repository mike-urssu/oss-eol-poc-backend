package cloud.jjoon.eolpoc.lifecycle

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface VersionRepository : JpaRepository<Version, Long> {
    fun findByOpensourceIdAndVersion(opensourceId: Long, version: String): Version?
    fun findAllByOpensourceId(opensourceId: Long): List<Version>

    /** 추출을 생략한 수집: 그 출처의 근거가 있는 버전의 마지막 확인 시각만 갱신한다. */
    @Modifying
    @Query(
        nativeQuery = true,
        value = "UPDATE oss_lifecycle SET last_verified_at = :now " +
            "WHERE version_id IN (SELECT version_id FROM eol_evidence WHERE source_id = :sourceId)",
    )
    fun touchVerifiedBySource(sourceId: Long, now: Instant)
}
