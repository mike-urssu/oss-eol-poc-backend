package cloud.jjoon.eolpoc.collection.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 출처 1개에 대한 수집 1회(eol_collection). status가 null이면 진행 중이다. */
@Entity
@Table(name = "eol_collection")
class CollectionRun(
    val opensourceId: Long,
    val sourceId: Long,
    @Enumerated(EnumType.STRING) val trigger: Trigger,
    val startedAt: Instant,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    @Enumerated(EnumType.STRING) var status: CollectionStatus? = null
    var skippedUnchanged: Boolean = false
    var errorCode: String? = null
    var errorMessage: String? = null
    var httpStatus: Int? = null
    var rawContent: String? = null
    var contentText: String? = null
    var contentHash: String? = null
    var promptVersion: String? = null
    var aiModel: String? = null
    var aiInputTokens: Long? = null
    var aiOutputTokens: Long? = null
    var candidateCount: Int? = null
    var finishedAt: Instant? = null
}
