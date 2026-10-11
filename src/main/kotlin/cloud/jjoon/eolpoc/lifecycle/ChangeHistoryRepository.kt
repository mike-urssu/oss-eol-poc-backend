package cloud.jjoon.eolpoc.lifecycle

import org.springframework.data.jpa.repository.JpaRepository

interface ChangeHistoryRepository : JpaRepository<ChangeHistory, Long> {
    fun findAllByVersionIdOrderByIdDesc(versionId: Long): List<ChangeHistory>
}
