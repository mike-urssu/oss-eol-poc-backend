package cloud.jjoon.eolpoc.extraction

data class ExtractedVersion(
    val version: String,
    val latestPatchVersion: Quoted?,
    val releaseDate: Quoted?,
    val eolDate: Quoted?,
    val securityEolDate: Quoted?,
    val support: Quoted?,
)
