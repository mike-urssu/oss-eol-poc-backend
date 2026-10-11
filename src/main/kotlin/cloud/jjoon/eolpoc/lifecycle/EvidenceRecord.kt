package cloud.jjoon.eolpoc.lifecycle

import cloud.jjoon.eolpoc.extraction.ExtractionMethod
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
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

interface EvidenceRepository : JpaRepository<EvidenceRecord, Long> {
    fun findAllByVersionId(versionId: Long): List<EvidenceRecord>
    fun findAllByVersionIdIn(versionIds: Collection<Long>): List<EvidenceRecord>

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO eol_evidence (version_id, field, value, source_id, source_url, quote, extraction_method, collection_id, created_at, verified_at)
            VALUES (:versionId, :field, :value, :sourceId, :sourceUrl, :quote, :method, :collectionId, :now, :now)
            ON CONFLICT (version_id, field, source_id, value)
            DO UPDATE SET verified_at = :now, quote = EXCLUDED.quote, collection_id = EXCLUDED.collection_id
        """,
    )
    fun upsert(
        versionId: Long, field: String, value: String, sourceId: Long, sourceUrl: String,
        quote: String, method: String, collectionId: Long, now: Instant,
    )

    @Modifying
    @Query("UPDATE EvidenceRecord e SET e.verifiedAt = :now WHERE e.sourceId = :sourceId")
    fun touchVerifiedBySource(sourceId: Long, now: Instant)
}
