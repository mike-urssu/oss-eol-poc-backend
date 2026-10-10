package cloud.jjoon.eolpoc.collection

import cloud.jjoon.eolpoc.common.EolProperties
import cloud.jjoon.eolpoc.opensource.CollectorType
import cloud.jjoon.eolpoc.opensource.SourceConfig
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.URI

data class Fetched(val httpStatus: Int, val rawContent: String, val contentText: String)

/** 외부에서 원본을 가져온다. 정보 추출은 하지 않는다(설계 4.1). */
interface Collector {
    val type: CollectorType
    fun fetch(url: String, config: SourceConfig): Fetched
}

class CollectionException(val code: String, message: String, val httpStatus: Int? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** 공통 HTTP: 타임아웃, User-Agent, 5xx·연결 오류 재시도(COL-07). */
@Component
class HttpFetcher(props: EolProperties) {
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

    fun get(url: String): Pair<Int, String> = try {
        retry.execute {
            val response = client.get().uri(URI(url)).retrieve().toEntity(String::class.java)
            response.statusCode.value() to (response.body ?: "")
        }!!
    } catch (e: RetryException) {
        val cause = e.cause ?: e
        when (cause) {
            is RestClientResponseException ->
                throw CollectionException("HTTP_ERROR", "HTTP ${cause.statusCode.value()} ($url)", cause.statusCode.value(), cause)
            else -> throw CollectionException("FETCH_ERROR", "${cause.message} ($url)", null, cause)
        }
    }
}

@Component
class WebCollector(private val http: HttpFetcher) : Collector {
    override val type = CollectorType.WEB

    override fun fetch(url: String, config: SourceConfig): Fetched {
        val (status, body) = http.get(url)
        val text = HtmlToText.convert(body, config.selector)
        if (text.isBlank()) throw CollectionException("EMPTY_CONTENT", "추출할 본문이 없습니다 (selector=${config.selector})", status)
        return Fetched(status, body, text)
    }
}

@Component
class ApiCollector(private val http: HttpFetcher) : Collector {
    override val type = CollectorType.API

    override fun fetch(url: String, config: SourceConfig): Fetched {
        val (status, body) = http.get(url)
        return Fetched(status, body, body)
    }
}
