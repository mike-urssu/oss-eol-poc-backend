package cloud.jjoon.eolpoc.extraction

/** AI로 출처 본문을 가져오고(web fetch), 그 본문에서 버전·EOL 정보를 뽑는다(설계 4.1, 4.2). */
interface Extractor {
    fun fetch(url: String): Fetched

    fun extract(request: ExtractionRequest): Extraction
}
