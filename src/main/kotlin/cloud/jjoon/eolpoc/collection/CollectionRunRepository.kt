package cloud.jjoon.eolpoc.collection

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface CollectionRunRepository : JpaRepository<CollectionRun, Long> {
    fun findFirstBySourceIdAndStatusAndIdLessThanOrderByIdDesc(sourceId: Long, status: CollectionStatus, id: Long): CollectionRun?
    fun findFirstBySourceIdAndFinishedAtIsNotNullOrderByIdDesc(sourceId: Long): CollectionRun?
    fun findAllByOpensourceIdOrderByIdDesc(opensourceId: Long, limit: Limit): List<CollectionRun>

    /** 기동 시 정리: 서버가 멈추며 끝나지 못한 수집을 실패로 닫는다. */
    @Modifying
    @Query(
        "UPDATE CollectionRun c SET c.status = cloud.jjoon.eolpoc.collection.CollectionStatus.FAILED, " +
            "c.errorCode = 'INTERRUPTED', c.errorMessage = '서버 재시작으로 중단됨', c.finishedAt = :now WHERE c.finishedAt IS NULL",
    )
    fun closeInterrupted(now: Instant): Int
}
