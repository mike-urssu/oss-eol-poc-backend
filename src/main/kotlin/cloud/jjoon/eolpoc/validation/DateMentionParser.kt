package cloud.jjoon.eolpoc.validation

import cloud.jjoon.eolpoc.lifecycle.domain.DatePrecision
import cloud.jjoon.eolpoc.lifecycle.domain.PreciseDate
import java.time.LocalDate
import java.time.YearMonth

/**
 * 문구 안의 날짜 표현을 모두 찾아 (기간 말일, 정밀도)로 바꾼다.
 * 일 → 월 → 연 순서로 찾고, 찾은 부분은 지워서 "2028-11-09" 안의 "2028"을 연 단위로 다시 읽지 않는다.
 */
object DateMentionParser {
    private const val MONTH =
        """\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?"""
    private const val ORD = """(?:st|nd|rd|th)?"""

    private val dayPatterns: List<Pair<Regex, (MatchResult) -> LocalDate>> = listOf(
        Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""") to { m -> date(m, 1, 2, 3) },
        Regex("""$MONTH\s+(\d{1,2})$ORD,?\s+(\d{4})""") to { m -> LocalDate.of(m.int(3), month(m, 1), m.int(2)) },
        Regex("""(\d{1,2})$ORD\s+$MONTH,?\s+(\d{4})""") to { m -> LocalDate.of(m.int(3), month(m, 2), m.int(1)) },
        Regex("""(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일""") to { m -> date(m, 1, 2, 3) },
    )
    private val monthPatterns: List<Pair<Regex, (MatchResult) -> YearMonth>> = listOf(
        Regex("""$MONTH,?\s+(\d{4})""") to { m -> YearMonth.of(m.int(2), month(m, 1)) },
        Regex("""(?<!\d)(\d{4})-(\d{1,2})(?!\d)""") to { m -> YearMonth.of(m.int(1), m.int(2)) },
        Regex("""(\d{4})년\s*(\d{1,2})월""") to { m -> YearMonth.of(m.int(1), m.int(2)) },
    )
    private val yearPattern = Regex("""(?<!\d)((?:19|20)\d{2})(?!\d)""")

    fun parse(text: String): Set<PreciseDate> {
        var rest = QuoteVerifier.normalize(text)
        val found = mutableSetOf<PreciseDate>()
        for ((regex, toDate) in dayPatterns) {
            rest = consume(rest, regex) { m -> PreciseDate(toDate(m), DatePrecision.DAY) }.also { found += it.second }.first
        }
        for ((regex, toMonth) in monthPatterns) {
            rest = consume(rest, regex) { m -> PreciseDate(toMonth(m).atEndOfMonth(), DatePrecision.MONTH) }.also { found += it.second }.first
        }
        yearPattern.findAll(rest).forEach { found += PreciseDate(LocalDate.of(it.int(1), 12, 31), DatePrecision.YEAR) }
        return found
    }

    /** 매치마다 날짜로 바꾸고(잘못된 날짜는 무시), 매치한 부분을 공백으로 지운 문자열을 돌려준다. */
    private fun consume(text: String, regex: Regex, toDate: (MatchResult) -> PreciseDate): Pair<String, List<PreciseDate>> {
        val dates = regex.findAll(text).mapNotNull { runCatching { toDate(it) }.getOrNull() }.toList()
        return regex.replace(text, " ") to dates
    }

    private fun MatchResult.int(group: Int) = groupValues[group].toInt()
    private fun date(m: MatchResult, y: Int, mo: Int, d: Int) = LocalDate.of(m.int(y), m.int(mo), m.int(d))
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private fun month(m: MatchResult, group: Int) = monthNames.indexOf(m.groupValues[group].take(3)) + 1
}
