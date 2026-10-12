package cloud.jjoon.eolpoc.common

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("eol")
data class EolProperties(
    val zone: ZoneId,
    val soonThresholdDays: Int,
    val http: Http,
    val ai: Ai,
    val cors: Cors,
) {
    data class Http(
        val connectTimeout: Duration,
        val readTimeout: Duration,
        val userAgent: String,
        val maxRetries: Long,
        val retryDelay: Duration,
    )

    data class Ai(val model: String, val effort: String?, val maxTokens: Long, val promptVersion: String)

    data class Cors(val allowedOrigins: List<String>)
}
