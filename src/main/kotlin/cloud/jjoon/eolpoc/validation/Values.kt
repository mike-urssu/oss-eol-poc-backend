package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.lifecycle.SupportDeclared
import cloud.jjoon.eolpoc.opensource.TrustLevel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class Field { RELEASE_DATE, LATEST_PATCH_VERSION, EOL_DATE, SECURITY_EOL_DATE, SUPPORT }

enum class DatePrecision { DAY, MONTH, YEAR }

/** 연·월 단위 날짜는 기간의 마지막 날로 저장하고 정밀도를 함께 보관한다(Q3). */
data class PreciseDate(val date: LocalDate, val precision: DatePrecision) {
    fun display(): String = when (precision) {
        DatePrecision.DAY -> date.toString()
        DatePrecision.MONTH -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        DatePrecision.YEAR -> date.year.toString()
    }
}

/** 버전 하나의 현재 값 또는 후보 값. */
data class VersionValues(
    val latestPatchVersion: String? = null,
    val releaseDate: PreciseDate? = null,
    val eolDate: PreciseDate? = null,
    val securityEolDate: PreciseDate? = null,
    val support: SupportDeclared? = null,
) {
    fun get(field: Field): Any? = when (field) {
        Field.RELEASE_DATE -> releaseDate
        Field.LATEST_PATCH_VERSION -> latestPatchVersion
        Field.EOL_DATE -> eolDate
        Field.SECURITY_EOL_DATE -> securityEolDate
        Field.SUPPORT -> support
    }

    fun display(field: Field): String? = when (field) {
        Field.RELEASE_DATE -> releaseDate?.display()
        Field.LATEST_PATCH_VERSION -> latestPatchVersion
        Field.EOL_DATE -> eolDate?.display()
        Field.SECURITY_EOL_DATE -> securityEolDate?.display()
        Field.SUPPORT -> support?.name
    }

    /** 값이 다른 필드. 날짜는 정밀도까지 비교하고, 한쪽만 null인 경우도 포함한다. */
    fun changedFields(other: VersionValues): List<Field> = Field.entries.filter { get(it) != other.get(it) }
}

data class Evidence(val field: Field, val value: String, val quote: String)

enum class ValidationStatus { APPROVED, REVIEW_REQUIRED, REJECTED }

data class Reason(val code: String, val field: Field?, val message: String, val status: ValidationStatus)

data class SourceRules(val trustLevel: TrustLevel, val versionPattern: Regex?)

data class ValidatedCandidate(
    val version: String,
    val values: VersionValues,
    val evidence: List<Evidence>,
    val status: ValidationStatus,
    val reasons: List<Reason>,
)
