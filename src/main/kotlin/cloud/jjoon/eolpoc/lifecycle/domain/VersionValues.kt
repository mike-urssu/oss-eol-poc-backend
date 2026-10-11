package cloud.jjoon.eolpoc.lifecycle.domain

/** 버전 하나의 현재 값 또는 후보 값. */
data class VersionValues(
    val latestPatchVersion: String? = null,
    val releaseDate: PreciseDate? = null,
    val eolDate: PreciseDate? = null,
    val securityEolDate: PreciseDate? = null,
    val support: SupportDeclared? = null,
) {
    fun get(field: Field): Any? = when (field) {
        Field.RELEASE_DATE -> releaseDate
        Field.LATEST_PATCH_VERSION -> latestPatchVersion
        Field.EOL_DATE -> eolDate
        Field.SECURITY_EOL_DATE -> securityEolDate
        Field.SUPPORT -> support
    }

    fun display(field: Field): String? = when (field) {
        Field.RELEASE_DATE -> releaseDate?.display()
        Field.LATEST_PATCH_VERSION -> latestPatchVersion
        Field.EOL_DATE -> eolDate?.display()
        Field.SECURITY_EOL_DATE -> securityEolDate?.display()
        Field.SUPPORT -> support?.name
    }

    /** 값이 다른 필드. 날짜는 정밀도까지 비교하고, 한쪽만 null인 경우도 포함한다. */
    fun changedFields(other: VersionValues): List<Field> = Field.entries.filter { get(it) != other.get(it) }
}
