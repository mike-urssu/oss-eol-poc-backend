package cloud.jjoon.eolpoc.opensource

import cloud.jjoon.eolpoc.collection.CollectionStatus
import cloud.jjoon.eolpoc.lifecycle.EolStatus
import cloud.jjoon.eolpoc.lifecycle.LifecycleQueryService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class OpensourceRequest(
    val slug: String = "",
    val name: String,
    val homepageUrl: String? = null,
    val description: String? = null,
    val active: Boolean = true,
)

data class OpensourceResponse(val slug: String, val name: String, val homepageUrl: String?, val description: String?, val active: Boolean)

data class SourceResponse(
    val id: Long,
    val url: String,
    val collectorType: CollectorType,
    val trustLevel: TrustLevel,
    val config: SourceConfig,
    val parserKey: String?,
    val active: Boolean,
)

@RestController
@RequestMapping("/api")
class OpensourceController(
    private val query: LifecycleQueryService,
    private val service: OpensourceService,
) {
    @GetMapping("/opensources")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) eolStatus: EolStatus?,
        @RequestParam(required = false) collectionStatus: CollectionStatus?,
    ) = query.list(q, eolStatus, collectionStatus)

    @GetMapping("/opensources/{slug}")
    fun detail(@PathVariable slug: String) = query.detail(slug)

    @GetMapping("/opensources/{slug}/versions/{version}")
    fun version(@PathVariable slug: String, @PathVariable version: String) = query.versionDetail(slug, version)

    @PostMapping("/opensources")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody r: OpensourceRequest) = service.create(r.slug, r.name, r.homepageUrl, r.description).toResponse()

    @PutMapping("/opensources/{slug}")
    fun update(@PathVariable slug: String, @RequestBody r: OpensourceRequest) =
        service.update(slug, r.name, r.homepageUrl, r.description, r.active).toResponse()

    @PostMapping("/opensources/{slug}/sources")
    @ResponseStatus(HttpStatus.CREATED)
    fun addSource(@PathVariable slug: String, @RequestBody r: SourceRequest) = service.addSource(slug, r).toResponse()

    @PutMapping("/sources/{id}")
    fun updateSource(@PathVariable id: Long, @RequestBody r: SourceRequest) = service.updateSource(id, r).toResponse()

    private fun Opensource.toResponse() = OpensourceResponse(slug, name, homepageUrl, description, active)
    private fun Source.toResponse() = SourceResponse(id!!, url, collectorType, trustLevel, config, parserKey, active)
}
