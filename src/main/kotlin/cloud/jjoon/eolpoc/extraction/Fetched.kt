package cloud.jjoon.eolpoc.extraction

/** AI가 web fetch로 가져온 출처 본문과 그 호출의 사용량. */
data class Fetched(val text: String, val model: String, val inputTokens: Long, val outputTokens: Long)
