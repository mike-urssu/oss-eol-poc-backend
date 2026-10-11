package cloud.jjoon.eolpoc.lifecycle.domain

import cloud.jjoon.eolpoc.extraction.domain.ExtractionMethod
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** eol_evidence: 어떤 출처의 어떤 문구가 어떤 값을 뒷받침하는가 (설계 D8). 쓰기는 upsert로만 한다. */
@Entity
@Table(name = "eol_evidence")
class EvidenceRecord(
    @Id val id: Long,
    val versionId: Long,
    @Enumerated(EnumType.STRING) val field: Field,
    val value: String,
    val sourceId: Long,
    val sourceUrl: String,
    val quote: String,
    @Enumerated(EnumType.STRING) val extractionMethod: ExtractionMethod,
    val collectionId: Long,
    val createdAt: Instant,
    val verifiedAt: Instant,
)
