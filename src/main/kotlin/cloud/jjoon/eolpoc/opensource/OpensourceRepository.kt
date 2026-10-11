package cloud.jjoon.eolpoc.opensource

import org.springframework.data.jpa.repository.JpaRepository

interface OpensourceRepository : JpaRepository<Opensource, Long> {
    fun findBySlug(slug: String): Opensource?
    fun findAllByActiveTrueOrderById(): List<Opensource>
}
