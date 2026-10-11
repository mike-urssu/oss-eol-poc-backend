package cloud.jjoon.eolpoc.opensource

import cloud.jjoon.eolpoc.common.badRequest
import cloud.jjoon.eolpoc.common.conflict
import cloud.jjoon.eolpoc.common.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Instant

/** Opensource·출처 등록과 수정 (REG-01~04). 삭제 대신 비활성화한다. */
@Service
@Transactional
class OpensourceService(
    private val opensources: OpensourceRepository,
    private val sources: SourceRepository,
) {
    fun create(slug: String, name: String, homepageUrl: String?, description: String?): Opensource {
        if (!Regex("[a-z0-9-]+").matches(slug)) throw badRequest("slug는 소문자, 숫자, -만 사용할 수 있습니다: $slug")
        if (opensources.findBySlug(slug) != null) throw conflict("이미 등록된 slug입니다: $slug")
        homepageUrl?.let(::requireHttpUrl)
        return opensources.save(Opensource(slug, name, homepageUrl, description))
    }

    fun update(slug: String, name: String, homepageUrl: String?, description: String?, active: Boolean): Opensource {
        homepageUrl?.let(::requireHttpUrl)
        return get(slug).apply {
            this.name = name
            this.homepageUrl = homepageUrl
            this.description = description
            this.active = active
            updatedAt = Instant.now()
        }
    }

    fun addSource(slug: String, request: SourceRequest): Source {
        requireHttpUrl(request.url)
        request.config.versionPattern?.let(::requireRegex)
        return sources.save(
            Source(get(slug), request.url, request.collectorType, request.trustLevel, request.config).apply {
                parserKey = request.parserKey
                active = request.active
            },
        )
    }

    fun updateSource(id: Long, request: SourceRequest): Source {
        requireHttpUrl(request.url)
        request.config.versionPattern?.let(::requireRegex)
        return (sources.findById(id).orElse(null) ?: throw notFound("출처를 찾을 수 없습니다: $id")).apply {
            url = request.url
            collectorType = request.collectorType
            trustLevel = request.trustLevel
            config = request.config
            parserKey = request.parserKey
            active = request.active
            updatedAt = Instant.now()
        }
    }

    private fun get(slug: String) = opensources.findBySlug(slug) ?: throw notFound("Opensource를 찾을 수 없습니다: $slug")

    private fun requireHttpUrl(url: String) {
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        if (scheme != "http" && scheme != "https") throw badRequest("http(s) URL만 허용합니다: $url")
    }

    private fun requireRegex(pattern: String) {
        runCatching { Regex(pattern) }.getOrElse { throw badRequest("버전 패턴이 올바른 정규식이 아닙니다: $pattern") }
    }
}
