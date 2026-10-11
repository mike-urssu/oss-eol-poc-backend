package cloud.jjoon.eolpoc.collection

import cloud.jjoon.eolpoc.common.EolProperties
import cloud.jjoon.eolpoc.opensource.CollectorType
import cloud.jjoon.eolpoc.opensource.SourceConfig
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.URI

/**
 * 출처에서 원본을 가져와 AI에 보낼 텍스트로 바꾼다. 정보 추출은 하지 않는다(설계 4.1).
 * WEB은 HTML을 텍스트로 바꾸고, API는 응답 본문을 그대로 쓴다.
 * 공통 HTTP: 타임아웃, User-Agent, 5xx·연결 오류 재시도(COL-07).
 */
@Component
class SourceFetcher(props: EolProperties) {
    private val client = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(props.http.connectTimeout)
                setReadTimeout(props.http.readTimeout)
            },
        )
        .defaultHeader(HttpHeaders.USER_AGENT, props.http.userAgent)
        .build()

    private val retry = RetryTemplate(
        RetryPolicy.builder()
            .maxRetries(props.http.maxRetries)
            .delay(props.http.retryDelay)
            .multiplier(2.0)
            .predicate { it is ResourceAccessException || it is HttpServerErrorException }
            .build(),
    )

    fun fetch(type: CollectorType, url: String, config: SourceConfig): Fetched = when (type) {
        CollectorType.WEB -> {
            val (status, body) = get(url)
            val text = HtmlToText.convert(body, config.selector)
            if (text.isBlank()) throw CollectionException("EMPTY_CONTENT", "추출할 본문이 없습니다 (selector=${config.selector})", status)
            Fetched(status, body, text)
        }
        CollectorType.API -> {
            val (status, body) = get(url)
            Fetched(status, body, body)
        }
        CollectorType.GITHUB -> throw CollectionException("UNSUPPORTED_COLLECTOR", "지원하지 않는 수집 방식: $type")
    }

    private fun get(url: String): Pair<Int, String> = try {
        retry.execute {
            val response = client.get().uri(URI(url)).retrieve().toEntity(String::class.java)
            response.statusCode.value() to (response.body ?: "")
        }
    } catch (e: RetryException) {
        when (val cause = e.cause) {
            is RestClientResponseException ->
                throw CollectionException("HTTP_ERROR", "HTTP ${cause.statusCode.value()} ($url)", cause.statusCode.value(), cause)
            else -> throw CollectionException("FETCH_ERROR", "${cause.message} ($url)", null, cause)
        }
    }
}
