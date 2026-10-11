package cloud.jjoon.eolpoc.extraction

/** 원본 텍스트에서 버전·EOL 정보를 뽑는다. 기본은 AI, 예외 출처만 전용 Parser(설계 4.2). */
interface Extractor {
    fun extract(request: ExtractionRequest): Extraction
}
