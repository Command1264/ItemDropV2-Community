package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCatalog
import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import com.github.command1264.itemdropv2.core.MinecraftLanguageRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

public class AtomicMinecraftLanguageRepository : MinecraftLanguageRepository {
    private val catalog = AtomicReference<MinecraftLanguageCatalog?>()

    override fun translation(key: String): String? = catalog.get()?.translation(key)

    public fun replace(value: MinecraftLanguageCatalog) {
        catalog.set(value)
    }
}

public fun interface MinecraftLanguageSource : AutoCloseable {
    public fun load(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
    ): MinecraftLanguageAssetResult

    override fun close() = Unit
}

public class OfficialMinecraftLanguageSource(
    private val englishSource: ClasspathEnglishLanguageSource,
    private val mojangSource: MojangLanguageAssetResolver,
) : MinecraftLanguageSource {
    override fun load(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
    ): MinecraftLanguageAssetResult =
        if (language == MinecraftLanguageCode.EN_US) {
            englishSource.load(language)
        } else {
            mojangSource.resolve(minecraftVersion, language)
        }

    override fun close() {
        mojangSource.close()
    }
}

public class ClasspathEnglishLanguageSource(
    private val resourceLoader: (String) -> InputStream?,
) {
    public fun load(language: MinecraftLanguageCode): MinecraftLanguageAssetResult {
        require(language == MinecraftLanguageCode.EN_US) { "classpath source only supports en_us" }
        val stream =
            resourceLoader(ENGLISH_RESOURCE)
                ?: return MinecraftLanguageAssetResult.Failed("server does not expose bundled en_us language data")
        return try {
            val bytes = stream.use(::readBounded)
            MinecraftLanguageAssetResult.Resolved(
                MinecraftLanguageAsset(language, bytes.sha1(), bytes),
            )
        } catch (error: IOException) {
            MinecraftLanguageAssetResult.Failed(
                "bundled en_us language data could not be read (${error.javaClass.simpleName})",
            )
        } catch (error: IllegalArgumentException) {
            MinecraftLanguageAssetResult.Failed(error.message ?: "bundled en_us language data is invalid")
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MinecraftLanguageCatalogParser.MAX_LANGUAGE_BYTES) {
                "bundled en_us language data is too large"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    public companion object {
        public const val ENGLISH_RESOURCE: String = "assets/minecraft/lang/en_us.json"
    }
}

public interface MinecraftLanguageTaskScheduler {
    public fun executeAsync(task: () -> Unit)

    public fun executeMain(task: () -> Unit)
}

public interface MinecraftLanguageLoadSink {
    public fun loaded(
        source: String,
        translationCount: Int,
    )

    public fun warn(diagnostic: String)
}

public class MinecraftLanguageLoadCoordinator(
    private val cache: MinecraftLanguageCache,
    private val source: MinecraftLanguageSource,
    private val parser: MinecraftLanguageCatalogParser,
    private val repository: AtomicMinecraftLanguageRepository,
    private val scheduler: MinecraftLanguageTaskScheduler,
    private val sink: MinecraftLanguageLoadSink,
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val started = AtomicBoolean(false)
    private val sourceClosed = AtomicBoolean(false)
    private val lifecycleLock = ReentrantLock()
    private val tasksCompleted = lifecycleLock.newCondition()
    private var runningTasks: Int = 0

    public fun start(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
    ) {
        check(started.compareAndSet(false, true)) { "Minecraft language loading has already started" }
        scheduler.executeAsync {
            if (!beginTask()) return@executeAsync
            try {
                val cacheAvailable = loadCache(minecraftVersion, language)
                if (!active.get()) return@executeAsync
                when (val result = source.load(minecraftVersion, language)) {
                    is MinecraftLanguageAssetResult.Resolved -> {
                        if (active.get()) {
                            val sourceLabel =
                                if (language == MinecraftLanguageCode.EN_US) "classpath" else "network"
                            loadSourceAsset(minecraftVersion, result.asset, sourceLabel)
                        }
                    }
                    is MinecraftLanguageAssetResult.Failed -> {
                        val diagnostic =
                            if (cacheAvailable) {
                                "${result.diagnostic}; using validated cache"
                            } else {
                                result.diagnostic
                            }
                        publishWarning(diagnostic)
                    }
                }
            } finally {
                completeTask()
            }
        }
    }

    private fun beginTask(): Boolean =
        lifecycleLock.withLock {
            if (!active.get()) return@withLock false
            runningTasks += 1
            true
        }

    private fun completeTask() {
        lifecycleLock.withLock {
            runningTasks -= 1
            check(runningTasks >= 0) { "Minecraft language task count became negative" }
            tasksCompleted.signalAll()
        }
    }

    private fun loadCache(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
    ): Boolean =
        when (val result = cache.read(minecraftVersion, language)) {
            MinecraftLanguageCacheReadResult.Missing -> false
            is MinecraftLanguageCacheReadResult.Loaded -> {
                publish(result.catalog, "cache")
                true
            }
            is MinecraftLanguageCacheReadResult.Invalid -> {
                publishWarning(result.diagnostic)
                false
            }
        }

    private fun loadSourceAsset(
        minecraftVersion: String,
        asset: MinecraftLanguageAsset,
        sourceLabel: String,
    ) {
        when (val parsed = parser.parse(asset.language, asset.bytes)) {
            is MinecraftLanguageParseResult.Invalid -> publishWarning(parsed.diagnostic)
            is MinecraftLanguageParseResult.Loaded -> {
                if (!active.get()) return
                val write = cache.write(minecraftVersion, asset.language, asset.bytes)
                if (write is MinecraftLanguageCacheWriteResult.Failed) {
                    publishWarning(write.diagnostic)
                }
                publish(parsed.catalog, sourceLabel)
            }
        }
    }

    private fun publish(
        catalog: MinecraftLanguageCatalog,
        source: String,
    ) {
        lifecycleLock.withLock {
            if (!active.get()) return
            scheduler.executeMain {
                if (active.get()) {
                    repository.replace(catalog)
                    sink.loaded(source, catalog.size)
                }
            }
        }
    }

    private fun publishWarning(diagnostic: String) {
        lifecycleLock.withLock {
            if (!active.get()) return
            scheduler.executeMain {
                if (active.get()) sink.warn(diagnostic)
            }
        }
    }

    public fun cancel() {
        lifecycleLock.withLock {
            active.set(false)
        }
        if (sourceClosed.compareAndSet(false, true)) {
            source.close()
        }
    }

    override fun close() {
        val sourceCloseFailure = runCatching(::cancel).exceptionOrNull()
        awaitTaskCompletion()
        if (sourceCloseFailure != null) {
            throw IllegalStateException("Minecraft language source could not be closed", sourceCloseFailure)
        }
    }

    private fun awaitTaskCompletion() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MILLIS)
        lifecycleLock.withLock {
            while (runningTasks > 0) {
                val remainingNanos = deadline - System.nanoTime()
                check(remainingNanos > 0) { "Minecraft language task did not stop within $CLOSE_TIMEOUT_MILLIS ms" }
                try {
                    tasksCompleted.awaitNanos(remainingNanos)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while stopping Minecraft language task", error)
                }
            }
        }
    }

    private companion object {
        private const val CLOSE_TIMEOUT_MILLIS: Long = 15_000
    }
}

private fun ByteArray.sha1(): String = MessageDigest.getInstance("SHA-1").digest(this).joinToString("") { byte -> "%02x".format(byte) }
