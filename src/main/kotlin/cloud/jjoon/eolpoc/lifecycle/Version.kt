package cloud.jjoon.eolpoc.lifecycle

import cloud.jjoon.eolpoc.opensource.Opensource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrimaryKeyJoinColumn
import jakarta.persistence.SecondaryTable
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/** 관리 대상 버전(oss_version)과 현재 지원 정책(oss_lifecycle, 1:1)을 한 엔티티로 다룬다. */
@Entity
@Table(name = "oss_version")
@SecondaryTable(name = "oss_lifecycle", pkJoinColumns = [PrimaryKeyJoinColumn(name = "version_id")])
class Version(
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "opensource_id")
    val opensource: Opensource,
    val version: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var latestPatchVersion: String? = null
    var releaseDate: LocalDate? = null
    @Enumerated(EnumType.STRING) var releaseDatePrecision: DatePrecision? = null
    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()

    @Column(table = "oss_lifecycle") var eolDate: LocalDate? = null
    @Column(table = "oss_lifecycle") @Enumerated(EnumType.STRING) var eolDatePrecision: DatePrecision? = null
    @Column(table = "oss_lifecycle") var securityEolDate: LocalDate? = null
    @Column(table = "oss_lifecycle") @Enumerated(EnumType.STRING) var securityEolDatePrecision: DatePrecision? = null
    @Column(table = "oss_lifecycle") @Enumerated(EnumType.STRING) var supportDeclared: SupportDeclared? = null
    @Column(table = "oss_lifecycle") var lastVerifiedAt: Instant? = null
    @Column(table = "oss_lifecycle", name = "updated_at") var lifecycleUpdatedAt: Instant = Instant.now()

    fun values() = VersionValues(
        latestPatchVersion = latestPatchVersion,
        releaseDate = releaseDate?.let { PreciseDate(it, releaseDatePrecision!!) },
        eolDate = eolDate?.let { PreciseDate(it, eolDatePrecision!!) },
        securityEolDate = securityEolDate?.let { PreciseDate(it, securityEolDatePrecision!!) },
        support = supportDeclared,
    )

    fun assign(values: VersionValues, now: Instant) {
        latestPatchVersion = values.latestPatchVersion
        releaseDate = values.releaseDate?.date
        releaseDatePrecision = values.releaseDate?.precision
        eolDate = values.eolDate?.date
        eolDatePrecision = values.eolDate?.precision
        securityEolDate = values.securityEolDate?.date
        securityEolDatePrecision = values.securityEolDate?.precision
        supportDeclared = values.support
        updatedAt = now
        lifecycleUpdatedAt = now
    }
}
