package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.extraction.ExtractedVersion
import cloud.jjoon.eolpoc.extraction.ExtractionResult
import cloud.jjoon.eolpoc.extraction.Quoted
import cloud.jjoon.eolpoc.lifecycle.domain.DatePrecision
import cloud.jjoon.eolpoc.lifecycle.domain.Field
import cloud.jjoon.eolpoc.lifecycle.domain.PreciseDate
import cloud.jjoon.eolpoc.lifecycle.domain.SupportDeclared
import cloud.jjoon.eolpoc.lifecycle.domain.VersionValues
import cloud.jjoon.eolpoc.opensource.domain.TrustLevel
import cloud.jjoon.eolpoc.validation.domain.Evidence
import cloud.jjoon.eolpoc.validation.domain.Reason
import cloud.jjoon.eolpoc.validation.domain.ValidationStatus
import cloud.jjoon.eolpoc.validation.domain.ValidationStatus.APPROVED
import cloud.jjoon.eolpoc.validation.domain.ValidationStatus.REJECTED
import cloud.jjoon.eolpoc.validation.domain.ValidationStatus.REVIEW_REQUIRED
import java.time.LocalDate
import java.time.YearMonth

/** AI 출력을 정규화하고 검증 규칙(설계 4.3, 4.4)을 적용한다. */
object CandidateValidator {
    private val OLDEST_EOL = LocalDate.of(1990, 1, 1)
    private const val MAX_EOL_YEARS_AHEAD = 15L

    fun validate(
        result: ExtractionResult,
        contentText: String,
        source: SourceRules,
        current: (version: String) -> VersionValues?,
        today: LocalDate,
    ): List<ValidatedCandidate> {
        val seen = mutableSetOf<String>()
        return result.versions.map { extracted ->
            val version = normalizeVersion(extracted.version)
            val duplicate = version.isNotEmpty() && !seen.add(version)
            validateOne(version, extracted, contentText, source, today, duplicate, current(version))
        }
    }

    private fun validateOne(
        version: String,
        extracted: ExtractedVersion,
        contentText: String,
        source: SourceRules,
        today: LocalDate,
        duplicate: Boolean,
        current: VersionValues?,
    ): ValidatedCandidate {
        val quoted: Map<Field, Quoted> = mapOf(
            Field.LATEST_PATCH_VERSION to extracted.latestPatchVersion,
            Field.RELEASE_DATE to extracted.releaseDate,
            Field.EOL_DATE to extracted.eolDate,
            Field.SECURITY_EOL_DATE to extracted.securityEolDate,
            Field.SUPPORT to extracted.support,
        ).filterValues { it != null }.mapValues { it.value!! }

        val values = VersionValues(
            latestPatchVersion = quoted[Field.LATEST_PATCH_VERSION]?.value?.trim()?.ifEmpty { null },
            releaseDate = quoted[Field.RELEASE_DATE]?.let { parseDate(it.value) },
            eolDate = quoted[Field.EOL_DATE]?.let { parseDate(it.value) },
            securityEolDate = quoted[Field.SECURITY_EOL_DATE]?.let { parseDate(it.value) },
            support = quoted[Field.SUPPORT]?.let { q -> SupportDeclared.entries.find { it.name == q.value.trim() } },
        )
        val reasons = mutableListOf<Reason>()

        if (version.isEmpty()) reasons += Reason("REQUIRED_MISSING", null, "버전이 비어 있음", REJECTED)
        if (duplicate) reasons += Reason("DUPLICATE_VERSION", null, "같은 결과에 버전 $version 이(가) 중복됨", REJECTED)
        if (source.versionPattern != null && version.isNotEmpty() && !source.versionPattern.matches(version)) {
            reasons += Reason("VERSION_PATTERN", null, "버전 $version 이(가) 패턴 ${source.versionPattern} 과 다름", REJECTED)
        }

        for ((field, q) in quoted) {
            val display = values.display(field)
            when {
                display == null && field == Field.SUPPORT ->
                    reasons += Reason("INVALID_VALUE", field, "지원 여부 값 '${q.value}' 은(는) SUPPORTED/EOL이 아님", REJECTED)
                display == null ->
                    reasons += Reason("DATE_FORMAT", field, "날짜 '${q.value}' 을(를) 해석할 수 없음", REJECTED)
                q.quote.isBlank() ->
                    reasons += Reason("EVIDENCE_MISSING", field, "근거 문구가 없음", REJECTED)
                else -> verifyQuote(field, q.quote, values, contentText)?.let { reasons += it }
            }
        }

        reasons += checkDateOrder(values) + checkDateRange(values, today) + checkChanges(current, values)
        if (source.trustLevel == TrustLevel.SECONDARY) {
            reasons += Reason("SOURCE_SECONDARY", null, "보조 출처의 값", REVIEW_REQUIRED)
        }

        val evidence = quoted.mapNotNull { (field, q) -> values.display(field)?.let { Evidence(field, it, q.quote) } }
        return ValidatedCandidate(version, values, evidence, strictest(reasons), reasons)
    }

    private fun verifyQuote(field: Field, quote: String, values: VersionValues, contentText: String): Reason? {
        if (!QuoteVerifier.containsQuote(contentText, quote)) {
            return Reason("QUOTE_NOT_FOUND", field, "근거 문구가 원문에 없음", REJECTED)
        }
        val matches = when (field) {
            Field.RELEASE_DATE -> QuoteVerifier.quoteMentionsDate(quote, values.releaseDate!!)
            Field.EOL_DATE -> QuoteVerifier.quoteMentionsDate(quote, values.eolDate!!)
            Field.SECURITY_EOL_DATE -> QuoteVerifier.quoteMentionsDate(quote, values.securityEolDate!!)
            Field.LATEST_PATCH_VERSION -> QuoteVerifier.quoteMentionsVersion(quote, values.latestPatchVersion!!)
            Field.SUPPORT -> true
        }
        return if (matches) null
        else Reason("QUOTE_VALUE_MISMATCH", field, "값 ${values.display(field)} 이(가) 근거 문구에서 확인되지 않음", REJECTED)
    }

    private fun checkDateOrder(values: VersionValues): List<Reason> {
        val release = values.releaseDate?.date ?: return emptyList()
        return listOf(
            Field.EOL_DATE to values.eolDate,
            Field.SECURITY_EOL_DATE to values.securityEolDate,
        ).mapNotNull { (field, end) ->
            if (end != null && release > end.date) Reason("DATE_ORDER", field, "릴리스일($release)이 종료일(${end.display()})보다 늦음", REJECTED)
            else null
        }
    }

    private fun checkDateRange(values: VersionValues, today: LocalDate): List<Reason> {
        val reasons = mutableListOf<Reason>()
        values.releaseDate?.let {
            // 월·연 정밀도는 기간 말일로 저장되므로 기간 시작일 기준으로 미래인지 본다
            val start = when (it.precision) {
                DatePrecision.DAY -> it.date
                DatePrecision.MONTH -> it.date.withDayOfMonth(1)
                DatePrecision.YEAR -> it.date.withDayOfYear(1)
            }
            if (start > today) reasons += Reason("DATE_SUSPICIOUS", Field.RELEASE_DATE, "릴리스일 ${it.display()} 이(가) 미래임", REVIEW_REQUIRED)
        }
        for ((field, end) in listOf(Field.EOL_DATE to values.eolDate, Field.SECURITY_EOL_DATE to values.securityEolDate)) {
            if (end == null) continue
            if (end.date < OLDEST_EOL || end.date > today.plusYears(MAX_EOL_YEARS_AHEAD)) {
                reasons += Reason("DATE_SUSPICIOUS", field, "종료일 ${end.display()} 이(가) 비정상 범위임", REVIEW_REQUIRED)
            }
        }
        return reasons
    }

    /**
     * VAL-09/10/12: 승인된 값이 있는 날짜·지원 여부 필드가 다른 값(없어지는 경우, 정밀도만 바뀌는 경우 포함)이 되면 검토한다.
     * 최신 패치 버전은 EOL 판단과 무관하고 자주 바뀌므로 다른 값으로 바뀌는 것은 허용하고, 없어질 때만 검토한다(설계 D4).
     */
    private fun checkChanges(current: VersionValues?, candidate: VersionValues): List<Reason> {
        if (current == null) return emptyList()
        return current.changedFields(candidate)
            .filter { current.get(it) != null }
            .filter { it != Field.LATEST_PATCH_VERSION || candidate.latestPatchVersion == null }
            .map { field ->
                val proposed = candidate.display(field) ?: "없음"
                Reason("VALUE_CHANGED", field, "승인된 값 ${current.display(field)} → 후보 값 $proposed", REVIEW_REQUIRED)
            }
    }

    private fun strictest(reasons: List<Reason>): ValidationStatus = when {
        reasons.any { it.status == REJECTED } -> REJECTED
        reasons.any { it.status == REVIEW_REQUIRED } -> REVIEW_REQUIRED
        else -> APPROVED
    }

    private fun normalizeVersion(version: String) = version.trim().removePrefix("v").removePrefix("V")

    /** AI 날짜 표기(yyyy-MM-dd | yyyy-MM | yyyy)를 기간 말일 + 정밀도로 바꾼다. 형식이 틀리면 null. */
    private fun parseDate(value: String): PreciseDate? {
        val v = value.trim()
        return runCatching {
            when {
                Regex("""\d{4}-\d{2}-\d{2}""").matches(v) -> PreciseDate(LocalDate.parse(v), DatePrecision.DAY)
                Regex("""\d{4}-\d{2}""").matches(v) -> PreciseDate(YearMonth.parse(v).atEndOfMonth(), DatePrecision.MONTH)
                Regex("""\d{4}""").matches(v) -> PreciseDate(LocalDate.of(v.toInt(), 12, 31), DatePrecision.YEAR)
                else -> null
            }
        }.getOrNull()
    }
}
