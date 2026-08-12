package com.github.command1264.itemdropv2.core

@InternalItemDropApi
public interface BackendProvider {
    public val id: String

    /** Artifact classifier that an administrator can use to pick the correct JAR. */
    public val artifactClassifier: String

    /** Suggests the artifact classifier appropriate for the detected server. */
    public fun recommendedArtifactClassifier(fingerprint: ServerFingerprint): String? = artifactClassifier

    /** Returns an empty list when this provider can run on [fingerprint]. */
    public fun incompatibilities(fingerprint: ServerFingerprint): List<String>

    public fun createBackend(fingerprint: ServerFingerprint): PresentationBackend

    /** Whether this edition exposes the Paper client-side translation setting. */
    public val supportsPaperClientSideTranslationSetting: Boolean
        get() = false

    /** Optional raw config fragment packaged only by an edition that owns the setting. */
    public val configurationFragmentResource: String?
        get() = null

    public fun createBackend(
        fingerprint: ServerFingerprint,
        settings: PresentationBackendSettings,
    ): PresentationBackend = createBackend(fingerprint)
}

@InternalItemDropApi
public data class PresentationBackendSettings(
    public val paperClientSideTranslationEnabled: Boolean = true,
)
