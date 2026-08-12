package com.github.command1264.itemdropv2.core

@InternalItemDropApi
public class BackendSelectionService {
    public fun select(
        providers: Iterable<BackendProvider>,
        fingerprint: ServerFingerprint,
    ): BackendSelectionResult {
        val candidates = providers.toList()
        return when {
            candidates.isEmpty() ->
                BackendSelectionResult.Rejected(
                    diagnostic = "No presentation backend provider was packaged in this artifact.",
                    recommendedArtifactClassifier = null,
                )
            candidates.size > 1 ->
                BackendSelectionResult.Rejected(
                    diagnostic =
                        "Multiple presentation backend providers were packaged: " +
                            candidates.joinToString { it.id },
                    recommendedArtifactClassifier = null,
                )
            else -> selectSingleProvider(candidates.single(), fingerprint)
        }
    }

    private fun selectSingleProvider(
        provider: BackendProvider,
        fingerprint: ServerFingerprint,
    ): BackendSelectionResult {
        val incompatibilities = provider.incompatibilities(fingerprint)
        return if (incompatibilities.isNotEmpty()) {
            BackendSelectionResult.Rejected(
                diagnostic = incompatibilities.joinToString(separator = "; "),
                recommendedArtifactClassifier = provider.recommendedArtifactClassifier(fingerprint),
            )
        } else {
            BackendSelectionResult.Selected(
                providerId = provider.id,
                artifactClassifier = provider.artifactClassifier,
                backend = provider.createBackend(fingerprint),
                provider = provider,
            )
        }
    }
}

@InternalItemDropApi
public sealed class BackendSelectionResult {
    public data class Selected(
        public val providerId: String,
        public val artifactClassifier: String,
        public val backend: PresentationBackend,
        public val provider: BackendProvider,
    ) : BackendSelectionResult()

    public data class Rejected(
        public val diagnostic: String,
        public val recommendedArtifactClassifier: String?,
    ) : BackendSelectionResult()
}
