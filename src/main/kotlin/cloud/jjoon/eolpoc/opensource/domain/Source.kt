package cloud.jjoon.eolpoc.opensource.domain

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
import java.time.Instant

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
