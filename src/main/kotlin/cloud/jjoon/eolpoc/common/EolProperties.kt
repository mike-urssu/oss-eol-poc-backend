package cloud.jjoon.eolpoc.common

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

@ConfigurationProperties("eol")
data class EolProperties(
    val zone: ZoneId,
    val soonThresholdDays: Int,
    val ai: Ai,
    val cors: Cors,
) {
    data class Ai(val model: String, val effort: String, val maxTokens: Long, val promptVersion: String)

    data class Cors(val allowedOrigins: List<String>)
}
