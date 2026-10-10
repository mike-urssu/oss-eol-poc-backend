package cloud.jjoon.eolpoc.lifecycle

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class EolStatus { SUPPORTED, EOL_SOON, EOL, UNKNOWN }

enum class SupportDeclared { SUPPORTED, EOL }

data class EolJudgement(val status: EolStatus, val rule: String, val message: String)

/** 요구사항 3.1 판정 규칙. 보안 지원 종료일은 사용하지 않는다(Q1). */
object EolStatusCalculator {
    fun judge(eolDate: LocalDate?, declared: SupportDeclared?, today: LocalDate, thresholdDays: Int): EolJudgement {
        if (eolDate != null) {
            val days = ChronoUnit.DAYS.between(today, eolDate)
            return when {
                days < 0 -> EolJudgement(EolStatus.EOL, "EOL_DATE_PASSED", "종료일($eolDate)이 지남")
                days <= thresholdDays ->
                    EolJudgement(EolStatus.EOL_SOON, "EOL_DATE_WITHIN_THRESHOLD", "종료일까지 ${days}일 (기준 ${thresholdDays}일)")
                else -> EolJudgement(EolStatus.SUPPORTED, "EOL_DATE_FUTURE", "종료일까지 ${days}일")
            }
        }
        return when (declared) {
            SupportDeclared.EOL -> EolJudgement(EolStatus.EOL, "DECLARED_EOL", "출처에 지원 종료 명시")
            SupportDeclared.SUPPORTED -> EolJudgement(EolStatus.SUPPORTED, "DECLARED_SUPPORTED", "출처에 지원 중 명시")
            null -> EolJudgement(EolStatus.UNKNOWN, "NO_INFORMATION", "판단할 정보 없음")
        }
    }
}
