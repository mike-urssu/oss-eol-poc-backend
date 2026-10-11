package cloud.jjoon.eolpoc.lifecycle.domain

import org.springframework.data.jpa.repository.JpaRepository

interface ChangeHistoryRepository : JpaRepository<ChangeHistory, Long> {
    fun findAllByVersionIdOrderByIdDesc(versionId: Long): List<ChangeHistory>
}
