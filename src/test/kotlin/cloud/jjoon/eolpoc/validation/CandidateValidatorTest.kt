package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.extraction.ExtractedVersion
import cloud.jjoon.eolpoc.extraction.ExtractionResult
import cloud.jjoon.eolpoc.extraction.Quoted
import cloud.jjoon.eolpoc.lifecycle.DatePrecision
import cloud.jjoon.eolpoc.lifecycle.Field
import cloud.jjoon.eolpoc.lifecycle.PreciseDate
import cloud.jjoon.eolpoc.lifecycle.SupportDeclared
import cloud.jjoon.eolpoc.lifecycle.VersionValues
import cloud.jjoon.eolpoc.opensource.TrustLevel
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CandidateValidatorTest {
    private val today = LocalDate.of(2026, 10, 11)

    private val text = """
        Version | Current minor | Supported | First Release | Final Release
        17 | 17.6 | Yes | September 26, 2024 | November 8, 2029
        16 | 16.10 | Yes | September 14, 2023 | November 9, 2028
        12 | 12.22 | No | October 3, 2019 | November 21, 2024
        Version 18 will be supported until November 2030.
    """.trimIndent()

    private fun validate(
        vararg versions: ExtractedVersion,
        trust: TrustLevel = TrustLevel.OFFICIAL,
        versionPattern: Regex? = null,
        current: Map<String, VersionValues> = emptyMap(),
        contentText: String = text,
    ) = CandidateValidator.validate(
        ExtractionResult(versions.toList()), contentText, SourceRules(trust, versionPattern), current::get, today,
    )

    private fun eolOnly(date: String, quote: String) = ExtractedVersion("9", null, null, Quoted(date, quote), null, null)

    private fun pg16(eolDate: Quoted? = Quoted("2028-11-09", "16 | 16.10 | Yes | September 14, 2023 | November 9, 2028")) =
        ExtractedVersion(
            version = "16",
            latestPatchVersion = Quoted("16.10", "16 | 16.10 | Yes"),
            releaseDate = Quoted("2023-09-14", "September 14, 2023"),
            eolDate = eolDate,
            securityEolDate = null,
            support = Quoted("SUPPORTED", "16 | 16.10 | Yes"),
        )

    @Test
    fun `근거 문구가 원문에 있고 값과 일치하면 신규 값은 자동 승인된다`() {
        val candidate = validate(pg16()).single()

        assertEquals(ValidationStatus.APPROVED, candidate.status)
        assertEquals(emptyList(), candidate.reasons)
        assertEquals(
            VersionValues(
                latestPatchVersion = "16.10",
                releaseDate = PreciseDate(LocalDate.of(2023, 9, 14), DatePrecision.DAY),
                eolDate = PreciseDate(LocalDate.of(2028, 11, 9), DatePrecision.DAY),
                securityEolDate = null,
                support = SupportDeclared.SUPPORTED,
            ),
            candidate.values,
        )
        assertEquals(
            Evidence(Field.EOL_DATE, "2028-11-09", "16 | 16.10 | Yes | September 14, 2023 | November 9, 2028"),
            candidate.evidence.single { it.field == Field.EOL_DATE },
        )
    }

    @Test
    fun `월 단위 날짜는 그 달 말일로 저장하고 정밀도를 남긴다`() {
        val v18 = ExtractedVersion("18", null, null, Quoted("2030-11", "supported until November 2030"), null, null)

        val candidate = validate(v18).single()

        assertEquals(ValidationStatus.APPROVED, candidate.status)
        assertEquals(PreciseDate(LocalDate.of(2030, 11, 30), DatePrecision.MONTH), candidate.values.eolDate)
        assertEquals("2030-11", candidate.evidence.single().value)
    }

    @Test
    fun `버전 앞의 v는 제거한다`() {
        val candidate = validate(pg16().copy(version = "v16")).single()
        assertEquals("16", candidate.version)
    }

    @Test
    fun `근거 문구가 원문에 없으면 거부된다`() {
        val candidate = validate(pg16(Quoted("2028-11-09", "PostgreSQL 16 is supported until November 9, 2028"))).single()

        assertEquals(ValidationStatus.REJECTED, candidate.status)
        assertEquals(listOf("QUOTE_NOT_FOUND" to Field.EOL_DATE), candidate.reasons.map { it.code to it.field })
    }

    @Test
    fun `근거 문구에 추출한 날짜가 없으면 거부된다`() {
        // 문구는 원문에 있지만 12 버전의 종료일이다
        val candidate = validate(pg16(Quoted("2028-11-09", "12 | 12.22 | No | October 3, 2019 | November 21, 2024"))).single()

        assertEquals(ValidationStatus.REJECTED, candidate.status)
        assertEquals(listOf("QUOTE_VALUE_MISMATCH" to Field.EOL_DATE), candidate.reasons.map { it.code to it.field })
    }

    @Test
    fun `근거 문구에 최신 패치 버전이 없으면 거부된다`() {
        val candidate = validate(pg16().copy(latestPatchVersion = Quoted("16.11", "16 | 16.10 | Yes"))).single()

        assertEquals(listOf("QUOTE_VALUE_MISMATCH" to Field.LATEST_PATCH_VERSION), candidate.reasons.map { it.code to it.field })
    }

    @Test
    fun `공백 대소문자 대시 차이는 같은 문구로 본다`() {
        val candidate = validate(
            eolOnly("2024-11-21", "12  |  12.22 | NO | october 3, 2019 | November 21, 2024"),
            contentText = "12 | 12.22 | No | October\u00a03,\u00a02019 | November 21, 2024 – end",
        ).single()

        assertEquals(ValidationStatus.APPROVED, candidate.status)
    }

    @Test
    fun `여러 날짜 표기를 근거 문구에서 읽는다`() {
        val day = "2028-11-09"
        listOf("2028-11-09", "2028/11/09", "2028.11.09", "9 Nov 2028", "Nov 9th, 2028", "November 9 2028", "2028년 11월 9일")
            .forEach { quote ->
                val candidate = validate(eolOnly(day, "ends $quote."), contentText = "Support ends $quote.").single()
                assertEquals(ValidationStatus.APPROVED, candidate.status, quote)
            }
    }

    @Test
    fun `월과 연 단위 표기도 정밀도까지 맞아야 한다`() {
        assertEquals(ValidationStatus.APPROVED, validate(eolOnly("2027-11", "Nov 2027"), contentText = "until Nov 2027").single().status)
        assertEquals(ValidationStatus.APPROVED, validate(eolOnly("2027-11", "2027년 11월"), contentText = "2027년 11월까지").single().status)
        assertEquals(ValidationStatus.APPROVED, validate(eolOnly("2027", "end of 2027"), contentText = "until end of 2027").single().status)
        // 원문은 일 단위인데 월 단위로 추출하면 정밀도가 달라 거부
        assertEquals(ValidationStatus.REJECTED, validate(eolOnly("2028-11", "November 9, 2028")).single().status)
    }

    private fun codes(candidate: ValidatedCandidate) = candidate.reasons.map { it.code }

    @Test
    fun `버전이 비어 있으면 거부된다`() {
        val candidate = validate(pg16().copy(version = " ")).single()
        assertEquals(ValidationStatus.REJECTED, candidate.status)
        assertEquals(listOf("REQUIRED_MISSING"), codes(candidate))
    }

    @Test
    fun `값이 있는데 근거 문구가 비어 있으면 거부된다`() {
        val candidate = validate(pg16(Quoted("2028-11-09", "  "))).single()
        assertEquals(ValidationStatus.REJECTED, candidate.status)
        assertEquals(listOf("EVIDENCE_MISSING"), codes(candidate))
    }

    @Test
    fun `날짜 형식이 틀리면 거부되고 값은 비워 둔다`() {
        listOf("2028-13-01", "late 2028", "11/09/2028").forEach { value ->
            val candidate = validate(pg16(Quoted(value, "November 9, 2028"))).single()
            assertEquals(listOf("DATE_FORMAT"), codes(candidate), value)
            assertEquals(null, candidate.values.eolDate)
        }
    }

    @Test
    fun `지원 여부 값이 정해진 값이 아니면 거부된다`() {
        val candidate = validate(pg16().copy(support = Quoted("MAYBE", "16 | 16.10 | Yes"))).single()
        assertEquals(listOf("INVALID_VALUE"), codes(candidate))
    }

    @Test
    fun `릴리스일이 종료일보다 늦으면 거부된다`() {
        val v = ExtractedVersion("12", null, Quoted("2024-11-21", "November 21, 2024"), Quoted("2019-10-03", "October 3, 2019"), null, null)
        val candidate = validate(v).single()
        assertEquals(ValidationStatus.REJECTED, candidate.status)
        assertEquals(listOf("DATE_ORDER"), codes(candidate))
    }

    @Test
    fun `의심스러운 날짜 범위는 검토 대상이다`() {
        val text = "released 2027-01-01, ends 2045-01-01, old 1980-01-01"
        val future = ExtractedVersion("1", null, Quoted("2027-01-01", "released 2027-01-01"), null, null, null)
        val farEol = eolOnly("2045-01-01", "ends 2045-01-01")
        val ancientEol = eolOnly("1980-01-01", "old 1980-01-01")

        listOf(future, farEol, ancientEol).forEach { v ->
            val candidate = validate(v, contentText = text).single()
            assertEquals(ValidationStatus.REVIEW_REQUIRED, candidate.status, v.toString())
            assertEquals(listOf("DATE_SUSPICIOUS"), codes(candidate))
        }
    }

    @Test
    fun `출처에 정한 버전 패턴과 다르면 거부된다`() {
        val candidate = validate(pg16().copy(version = "16.10"), versionPattern = Regex("""\d+""")).single()
        assertEquals(ValidationStatus.REJECTED, candidate.status)
        assertEquals(listOf("VERSION_PATTERN"), codes(candidate))
        assertEquals(ValidationStatus.APPROVED, validate(pg16(), versionPattern = Regex("""\d+""")).single().status)
    }

    @Test
    fun `보조 출처의 값은 검토 대상이다`() {
        val candidate = validate(pg16(), trust = TrustLevel.SECONDARY).single()
        assertEquals(ValidationStatus.REVIEW_REQUIRED, candidate.status)
        assertEquals(listOf("SOURCE_SECONDARY"), codes(candidate))
    }

    @Test
    fun `같은 결과 안의 중복 버전은 두 번째만 거부된다`() {
        val (first, second) = validate(pg16(), pg16())
        assertEquals(ValidationStatus.APPROVED, first.status)
        assertEquals(ValidationStatus.REJECTED, second.status)
        assertEquals(listOf("DUPLICATE_VERSION"), codes(second))
    }

    private val approved16 = VersionValues(
        latestPatchVersion = "16.9",
        releaseDate = PreciseDate(LocalDate.of(2023, 9, 14), DatePrecision.DAY),
        eolDate = PreciseDate(LocalDate.of(2028, 11, 9), DatePrecision.DAY),
        support = SupportDeclared.SUPPORTED,
    )

    @Test
    fun `승인된 값과 같으면 승인된다`() {
        val candidate = validate(pg16(), current = mapOf("16" to approved16)).single()
        assertEquals(ValidationStatus.APPROVED, candidate.status)
    }

    @Test
    fun `승인된 날짜가 다른 값으로 바뀌면 검토 대상이다`() {
        val current = approved16.copy(eolDate = PreciseDate(LocalDate.of(2028, 11, 16), DatePrecision.DAY))

        val candidate = validate(pg16(), current = mapOf("16" to current)).single()

        assertEquals(ValidationStatus.REVIEW_REQUIRED, candidate.status)
        assertEquals(listOf("VALUE_CHANGED" to Field.EOL_DATE), candidate.reasons.map { it.code to it.field })
        assertEquals("승인된 값 2028-11-16 → 후보 값 2028-11-09", candidate.reasons.single().message)
    }

    @Test
    fun `승인된 날짜가 사라지면 검토 대상이다`() {
        val candidate = validate(pg16(eolDate = null), current = mapOf("16" to approved16)).single()

        assertEquals(ValidationStatus.REVIEW_REQUIRED, candidate.status)
        assertEquals("승인된 값 2028-11-09 → 후보 값 없음", candidate.reasons.single().message)
    }

    @Test
    fun `정밀도만 바뀌어도 검토 대상이다`() {
        val current = approved16.copy(eolDate = PreciseDate(LocalDate.of(2028, 11, 30), DatePrecision.MONTH))
        val candidate = validate(pg16(), current = mapOf("16" to current)).single()
        assertEquals(listOf("VALUE_CHANGED" to Field.EOL_DATE), candidate.reasons.map { it.code to it.field })
    }

    @Test
    fun `승인된 지원 여부가 바뀌면 검토 대상이다`() {
        val current = approved16.copy(support = SupportDeclared.EOL)
        val candidate = validate(pg16(), current = mapOf("16" to current)).single()
        assertEquals(listOf("VALUE_CHANGED" to Field.SUPPORT), candidate.reasons.map { it.code to it.field })
    }

    @Test
    fun `비어 있던 값이 새로 채워지면 자동 승인된다`() {
        val current = VersionValues(latestPatchVersion = "16.10")
        assertEquals(ValidationStatus.APPROVED, validate(pg16(), current = mapOf("16" to current)).single().status)
    }

    @Test
    fun `최신 패치 버전 변경은 검토 없이 승인된다`() {
        val candidate = validate(pg16(), current = mapOf("16" to approved16)).single()
        assertEquals("16.10", candidate.values.latestPatchVersion)
        assertEquals(ValidationStatus.APPROVED, candidate.status)
    }

    @Test
    fun `승인된 최신 패치 버전이 사라지면 검토 대상이다`() {
        val candidate = validate(pg16().copy(latestPatchVersion = null), current = mapOf("16" to approved16)).single()
        assertEquals(listOf("VALUE_CHANGED" to Field.LATEST_PATCH_VERSION), candidate.reasons.map { it.code to it.field })
    }
}
