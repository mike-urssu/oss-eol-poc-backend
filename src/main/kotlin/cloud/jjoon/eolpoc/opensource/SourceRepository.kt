package cloud.jjoon.eolpoc.opensource

import org.springframework.data.jpa.repository.JpaRepository

interface SourceRepository : JpaRepository<Source, Long> {
    fun findAllByOpensourceIdOrderById(opensourceId: Long): List<Source>
}
