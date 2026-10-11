package cloud.jjoon.eolpoc.opensource.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "opensource")
class Opensource(
    val slug: String,
    var name: String,
    var homepageUrl: String?,
    var description: String?,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var active: Boolean = true
    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()
}
