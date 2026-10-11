package cloud.jjoon.eolpoc.lifecycle.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 연·월 단위 날짜는 기간의 마지막 날로 저장하고 정밀도를 함께 보관한다(Q3). */
data class PreciseDate(val date: LocalDate, val precision: DatePrecision) {
    fun display(): String = when (precision) {
        DatePrecision.DAY -> date.toString()
        DatePrecision.MONTH -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        DatePrecision.YEAR -> date.year.toString()
    }
}
