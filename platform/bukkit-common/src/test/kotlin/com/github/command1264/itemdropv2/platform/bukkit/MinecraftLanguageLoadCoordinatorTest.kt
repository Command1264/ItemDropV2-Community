package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MinecraftLanguageLoadCoordinatorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `loads asset off thread and publishes immutable catalog on main thread`() {
        val scheduler = ManualLanguageTaskScheduler()
        val repository = AtomicMinecraftLanguageRepository()
        val notices = mutableListOf<String>()
        val language = MinecraftLanguageCode.parse("zh_tw").getOrThrow()
        val bytes = """{"block.minecraft.stone":"石頭"}""".toByteArray(Charsets.UTF_8)
        val source =
            MinecraftLanguageSource { _, _ ->
                MinecraftLanguageAssetResult.Resolved(MinecraftLanguageAsset(language, "hash", bytes))
            }
        val coordinator =
            MinecraftLanguageLoadCoordinator(
                cache = MinecraftLanguageCache(directory, MinecraftLanguageCatalogParser()),
                source = source,
                parser = MinecraftLanguageCatalogParser(),
                repository = repository,
                scheduler = scheduler,
                sink = RecordingLanguageLoadSink(notices),
            )

        coordinator.start("26.2", language)
        assertNull(repository.translation("block.minecraft.stone"))
        scheduler.runAsync()
        assertNull(repository.translation("block.minecraft.stone"))
        scheduler.runMain()

        assertEquals("石頭", repository.translation("block.minecraft.stone"))
        assertEquals(listOf("loaded:network:1"), notices)
    }

    @Test
    fun `close prevents late callback from publishing`() {
        val scheduler = ManualLanguageTaskScheduler()
        val repository = AtomicMinecraftLanguageRepository()
        val language = MinecraftLanguageCode.EN_US
        val bytes = """{"block.minecraft.stone":"Stone"}""".toByteArray(Charsets.UTF_8)
        val coordinator =
            MinecraftLanguageLoadCoordinator(
                cache = MinecraftLanguageCache(directory, MinecraftLanguageCatalogParser()),
                source =
                    MinecraftLanguageSource { _, _ ->
                        MinecraftLanguageAssetResult.Resolved(MinecraftLanguageAsset(language, "hash", bytes))
                    },
                parser = MinecraftLanguageCatalogParser(),
                repository = repository,
                scheduler = scheduler,
                sink = RecordingLanguageLoadSink(mutableListOf()),
            )

        coordinator.start("1.14.4", language)
        scheduler.runAsync()
        coordinator.close()
        scheduler.runMain()

        assertNull(repository.translation("block.minecraft.stone"))
    }

    @Test
    fun `close waits for in-flight work to finish before returning`() {
        val scheduler = ManualLanguageTaskScheduler()
        val sourceEntered = CountDownLatch(1)
        val releaseSource = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val coordinator =
            MinecraftLanguageLoadCoordinator(
                cache = MinecraftLanguageCache(directory, MinecraftLanguageCatalogParser()),
                source =
                    MinecraftLanguageSource { _, language ->
                        sourceEntered.countDown()
                        check(releaseSource.await(2, TimeUnit.SECONDS)) { "test source was not released" }
                        MinecraftLanguageAssetResult.Resolved(
                            MinecraftLanguageAsset(
                                language,
                                "hash",
                                """{"block.minecraft.stone":"Stone"}""".toByteArray(Charsets.UTF_8),
                            ),
                        )
                    },
                parser = MinecraftLanguageCatalogParser(),
                repository = AtomicMinecraftLanguageRepository(),
                scheduler = scheduler,
                sink = RecordingLanguageLoadSink(mutableListOf()),
            )

        coordinator.start("1.14.4", MinecraftLanguageCode.EN_US)
        val worker = thread(name = "language-load-test") { scheduler.runAsync() }
        assertTrue(sourceEntered.await(1, TimeUnit.SECONDS))
        val closer =
            thread(name = "language-close-test") {
                coordinator.close()
                closeCompleted.countDown()
            }

        try {
            assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseSource.countDown()
            worker.join(2_000)
            closer.join(2_000)
        }
        assertFalse(worker.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(closeCompleted.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `close cancels a blocking source before waiting for task completion`() {
        val scheduler = ManualLanguageTaskScheduler()
        val sourceEntered = CountDownLatch(1)
        val sourceClosed = CountDownLatch(1)
        val source =
            object : MinecraftLanguageSource {
                override fun load(
                    minecraftVersion: String,
                    language: MinecraftLanguageCode,
                ): MinecraftLanguageAssetResult {
                    sourceEntered.countDown()
                    check(sourceClosed.await(2, TimeUnit.SECONDS)) { "test source was not closed" }
                    return MinecraftLanguageAssetResult.Failed("cancelled")
                }

                override fun close() {
                    sourceClosed.countDown()
                }
            }
        val coordinator =
            MinecraftLanguageLoadCoordinator(
                cache = MinecraftLanguageCache(directory, MinecraftLanguageCatalogParser()),
                source = source,
                parser = MinecraftLanguageCatalogParser(),
                repository = AtomicMinecraftLanguageRepository(),
                scheduler = scheduler,
                sink = RecordingLanguageLoadSink(mutableListOf()),
            )

        coordinator.start("26.2", MinecraftLanguageCode.EN_US)
        val worker = thread(name = "language-cancellation-test") { scheduler.runAsync() }
        assertTrue(sourceEntered.await(1, TimeUnit.SECONDS))

        coordinator.close()
        worker.join(2_000)

        assertTrue(sourceClosed.await(0, TimeUnit.MILLISECONDS))
        assertFalse(worker.isAlive)
    }

    @Test
    fun `cancel prevents late publication without waiting for in-flight work`() {
        val scheduler = ManualLanguageTaskScheduler()
        val sourceEntered = CountDownLatch(1)
        val releaseSource = CountDownLatch(1)
        val notices = mutableListOf<String>()
        val coordinator =
            MinecraftLanguageLoadCoordinator(
                cache = MinecraftLanguageCache(directory, MinecraftLanguageCatalogParser()),
                source =
                    object : MinecraftLanguageSource {
                        override fun load(
                            minecraftVersion: String,
                            language: MinecraftLanguageCode,
                        ): MinecraftLanguageAssetResult {
                            sourceEntered.countDown()
                            check(releaseSource.await(2, TimeUnit.SECONDS)) { "test source was not released" }
                            return MinecraftLanguageAssetResult.Failed("cancelled")
                        }

                        override fun close() {
                            releaseSource.countDown()
                        }
                    },
                parser = MinecraftLanguageCatalogParser(),
                repository = AtomicMinecraftLanguageRepository(),
                scheduler = scheduler,
                sink = RecordingLanguageLoadSink(notices),
            )

        coordinator.start("26.2", MinecraftLanguageCode.parse("zh_tw").getOrThrow())
        val worker = thread(name = "language-nonblocking-cancel-test") { scheduler.runAsync() }
        assertTrue(sourceEntered.await(1, TimeUnit.SECONDS))

        coordinator.cancel()
        worker.join(2_000)
        scheduler.runMain()
        coordinator.close()

        assertFalse(worker.isAlive)
        assertEquals(emptyList<String>(), notices)
    }

    private class ManualLanguageTaskScheduler : MinecraftLanguageTaskScheduler {
        private val asyncTasks = mutableListOf<() -> Unit>()
        private val mainTasks = mutableListOf<() -> Unit>()

        override fun executeAsync(task: () -> Unit) {
            asyncTasks += task
        }

        override fun executeMain(task: () -> Unit) {
            mainTasks += task
        }

        fun runAsync() {
            val tasks = asyncTasks.toList()
            asyncTasks.clear()
            tasks.forEach { it() }
        }

        fun runMain() {
            val tasks = mainTasks.toList()
            mainTasks.clear()
            tasks.forEach { it() }
        }
    }

    private class RecordingLanguageLoadSink(
        private val notices: MutableList<String>,
    ) : MinecraftLanguageLoadSink {
        override fun loaded(
            source: String,
            translationCount: Int,
        ) {
            notices += "loaded:$source:$translationCount"
        }

        override fun warn(diagnostic: String) {
            notices += "warn:$diagnostic"
        }
    }
}
