package cloud.jjoon.eolpoc.lifecycle

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class EolStatusCalculatorTest {
    private val today = LocalDate.of(2026, 10, 11)

    private fun judge(eolDate: LocalDate?, declared: SupportDeclared? = null, threshold: Int = 90) =
        EolStatusCalculator.judge(eolDate, declared, today, threshold)

    @Test
    fun `종료일이 지났으면 EOL`() {
        val result = judge(LocalDate.of(2026, 10, 10))
        assertEquals(EolStatus.EOL, result.status)
        assertEquals("EOL_DATE_PASSED", result.rule)
    }

    @Test
    fun `종료일 당일은 아직 EOL이 아니라 종료 임박`() {
        assertEquals(EolStatus.EOL_SOON, judge(today).status)
    }

    @Test
    fun `종료일까지 기준일 이하면 종료 임박이고 남은 일수를 설명한다`() {
        val result = judge(LocalDate.of(2026, 11, 25))
        assertEquals(EolStatus.EOL_SOON, result.status)
        assertEquals("종료일까지 45일 (기준 90일)", result.message)
    }

    @Test
    fun `기준일보다 하루 더 남으면 지원 중`() {
        assertEquals(EolStatus.SUPPORTED, judge(today.plusDays(91)).status)
    }

    @Test
    fun `기준일 설정을 바꾸면 판정이 바뀐다`() {
        val eol = today.plusDays(60)
        assertEquals(EolStatus.EOL_SOON, judge(eol, threshold = 90).status)
        assertEquals(EolStatus.SUPPORTED, judge(eol, threshold = 30).status)
    }

    @Test
    fun `종료일이 있으면 출처의 지원 여부 명시보다 날짜가 우선한다`() {
        assertEquals(EolStatus.EOL, judge(LocalDate.of(2020, 1, 1), SupportDeclared.SUPPORTED).status)
    }

    @Test
    fun `종료일 없이 지원 종료가 명시되면 EOL`() {
        val result = judge(null, SupportDeclared.EOL)
        assertEquals(EolStatus.EOL, result.status)
        assertEquals("DECLARED_EOL", result.rule)
    }

    @Test
    fun `종료일 없이 지원 중이 명시되면 지원 중`() {
        assertEquals(EolStatus.SUPPORTED, judge(null, SupportDeclared.SUPPORTED).status)
    }

    @Test
    fun `정보가 없으면 미확인`() {
        val result = judge(null, null)
        assertEquals(EolStatus.UNKNOWN, result.status)
        assertEquals("NO_INFORMATION", result.rule)
    }
}
