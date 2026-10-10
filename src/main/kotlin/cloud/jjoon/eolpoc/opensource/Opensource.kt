package cloud.jjoon.eolpoc.opensource

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

enum class TrustLevel { OFFICIAL, SECONDARY }

enum class CollectorType { API, WEB, GITHUB }

@Entity
@Table(name = "opensource")
class Opensource(
    val slug: String,
    var name: String,
    var homepageUrl: String?,
    var description: String?,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var active: Boolean = true
    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()
}

/** source_registry.config (jsonb). 설계 4.1 */
data class SourceConfig(
    val selector: String? = null,
    val versionHint: String? = null,
    val versionPattern: String? = null,
)

@Entity
@Table(name = "source_registry")
class Source(
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "opensource_id")
    val opensource: Opensource,
    var url: String,
    @Enumerated(EnumType.STRING) var collectorType: CollectorType,
    @Enumerated(EnumType.STRING) var trustLevel: TrustLevel,
    @JdbcTypeCode(SqlTypes.JSON) var config: SourceConfig,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var parserKey: String? = null
    var active: Boolean = true
    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()
}

interface OpensourceRepository : JpaRepository<Opensource, Long> {
    fun findBySlug(slug: String): Opensource?
    fun findAllByActiveTrueOrderById(): List<Opensource>
}

interface SourceRepository : JpaRepository<Source, Long> {
    fun findAllByOpensourceIdOrderById(opensourceId: Long): List<Source>
}
