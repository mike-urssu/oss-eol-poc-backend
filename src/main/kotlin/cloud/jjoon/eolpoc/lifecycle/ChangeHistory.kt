package cloud.jjoon.eolpoc.lifecycle

import cloud.jjoon.eolpoc.validation.Field
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "eol_change_history")
class ChangeHistory(
    val versionId: Long,
    @Enumerated(EnumType.STRING) val field: Field,
    val oldValue: String?,
    val newValue: String?,
    val candidateId: Long,
    val changedBy: String,
    val changedAt: Instant,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

interface ChangeHistoryRepository : JpaRepository<ChangeHistory, Long> {
    fun findAllByVersionIdOrderByIdDesc(versionId: Long): List<ChangeHistory>
}
