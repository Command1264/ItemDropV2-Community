package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BackendSelectionServiceTest {
    private val fingerprint =
        ServerFingerprint(
            platform = ServerPlatform.PAPER,
            minecraftVersion = "1.21.11",
            implementationVersion = "Paper-test",
        )
    private val service = BackendSelectionService()

    @Test
    fun `selects the sole compatible provider`() {
        val backend = FakeBackend()
        val result = service.select(listOf(FakeProvider(backend = backend)), fingerprint)

        val selected = assertInstanceOf(BackendSelectionResult.Selected::class.java, result)
        assertEquals("fake", selected.providerId)
        assertEquals("universal", selected.artifactClassifier)
        assertSame(backend, selected.backend)
    }

    @Test
    fun `rejects an artifact without a provider`() {
        val result = service.select(emptyList(), fingerprint)

        val rejected = assertInstanceOf(BackendSelectionResult.Rejected::class.java, result)
        assertEquals(null, rejected.recommendedArtifactClassifier)
    }

    @Test
    fun `rejects an artifact with multiple providers`() {
        val result = service.select(listOf(FakeProvider(), FakeProvider(id = "duplicate")), fingerprint)

        val rejected = assertInstanceOf(BackendSelectionResult.Rejected::class.java, result)
        assertEquals(null, rejected.recommendedArtifactClassifier)
    }

    @Test
    fun `rejects an incompatible provider with its artifact recommendation`() {
        val provider =
            FakeProvider(
                incompatibilities = listOf("Minecraft version mismatch"),
                classifier = "paper-1.21.11-nms",
            )

        val result = service.select(listOf(provider), fingerprint)

        val rejected = assertInstanceOf(BackendSelectionResult.Rejected::class.java, result)
        assertEquals("Minecraft version mismatch", rejected.diagnostic)
        assertEquals("paper-1.21.11-nms", rejected.recommendedArtifactClassifier)
    }

    private class FakeProvider(
        override val id: String = "fake",
        private val backend: PresentationBackend = FakeBackend(),
        private val incompatibilities: List<String> = emptyList(),
        private val classifier: String = "universal",
        private val recommendation: String = classifier,
    ) : BackendProvider {
        override val artifactClassifier: String = classifier

        override fun recommendedArtifactClassifier(fingerprint: ServerFingerprint): String = recommendation

        override fun incompatibilities(fingerprint: ServerFingerprint): List<String> = incompatibilities

        override fun createBackend(fingerprint: ServerFingerprint): PresentationBackend = backend
    }

    private class FakeBackend : PresentationBackend {
        override val id: String = "fake"

        override fun activate() = Unit

        override fun present(presentation: ItemPresentation): PresentationResult = PresentationResult.Applied

        override fun close() = Unit
    }
}
