package cloud.jjoon.eolpoc.extraction

/** AI 구조화 출력 스키마 (설계 4.2). 모든 값은 원문 근거 문구(quote)와 함께 온다. */
data class ExtractionResult(val versions: List<ExtractedVersion>)
