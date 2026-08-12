package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftItemRarity
import org.bukkit.Bukkit
import org.bukkit.UnsafeValues
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

public fun interface BukkitItemRarityResolver {
    public fun resolve(itemStack: ItemStack): MinecraftItemRarity
}

@Suppress("DEPRECATION")
public fun createBukkitItemRarityResolver(
    minecraftVersion: String,
    warningSink: DisplayWarningSink,
): BukkitItemRarityResolver =
    VersionAwareBukkitItemRarityResolver(
        minecraftVersion = minecraftVersion,
        nativeRarityAccess =
            ReflectiveNativeItemRarityAccess(
                unsafeValuesProvider = { Bukkit.getUnsafe() },
                warningSink = warningSink,
            ),
    )

internal fun interface NativeItemRarityAccess {
    fun resolve(itemStack: ItemStack): MinecraftItemRarity?
}

internal class VersionAwareBukkitItemRarityResolver(
    private val minecraftVersion: String,
    private val nativeRarityAccess: NativeItemRarityAccess,
    private val rarityCatalog: MinecraftRarityCatalog = MinecraftRarityCatalog(),
) : BukkitItemRarityResolver {
    override fun resolve(itemStack: ItemStack): MinecraftItemRarity =
        nativeRarityAccess.resolve(itemStack)
            ?: rarityCatalog.resolve(
                materialName = itemStack.type.name,
                enchanted = itemStack.enchantments.isNotEmpty(),
                minecraftVersion = minecraftVersion,
            )
}

/**
 * 依序使用現代 Bukkit ItemMeta rarity component 與舊 Paper UnsafeValues API。
 *
 * 反射只針對公開 Bukkit/Paper API 名稱；不載入 CraftBukkit 或 net.minecraft class。
 */
internal class ReflectiveNativeItemRarityAccess(
    private val unsafeValuesProvider: () -> Any?,
    private val warningSink: DisplayWarningSink,
) : NativeItemRarityAccess {
    private val modernFailureReported = AtomicBoolean()
    private val paperFailureReported = AtomicBoolean()

    override fun resolve(itemStack: ItemStack): MinecraftItemRarity? = readModernMetaRarity(itemStack) ?: readPaperStackRarity(itemStack)

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun readModernMetaRarity(itemStack: ItemStack): MinecraftItemRarity? {
        val meta = itemStack.itemMeta ?: return null
        val capability = modernRarityCapabilityByClass.get(ItemMeta::class.java)
        if (capability !is ModernRarityCapability.Supported) return null
        return try {
            if (capability.hasRarity.invoke(meta) != true) {
                null
            } else {
                mapRarity(capability.getRarity.invoke(meta))?.let { base ->
                    if (itemStack.enchantments.isEmpty()) base else base.whenEnchanted()
                }
            }
        } catch (error: ReflectiveOperationException) {
            reportOnce(modernFailureReported, "Bukkit ItemMeta rarity read failed", error)
            null
        } catch (error: LinkageError) {
            reportOnce(modernFailureReported, "Bukkit ItemMeta rarity linkage failed", error)
            null
        } catch (error: RuntimeException) {
            reportOnce(modernFailureReported, "Bukkit ItemMeta rarity read failed", error)
            null
        }
    }

    @Suppress("DEPRECATION", "ReturnCount", "TooGenericExceptionCaught")
    private fun readPaperStackRarity(itemStack: ItemStack): MinecraftItemRarity? {
        val unsafeValues = unsafeValuesProvider() ?: return null
        val capability = paperRarityCapabilityByClass.get(UnsafeValues::class.java)
        if (capability !is PaperRarityCapability.Supported) return null
        return try {
            mapRarity(capability.method.invoke(unsafeValues, itemStack))
        } catch (error: ReflectiveOperationException) {
            reportOnce(paperFailureReported, "Paper stack rarity read failed", error)
            null
        } catch (error: LinkageError) {
            reportOnce(paperFailureReported, "Paper stack rarity linkage failed", error)
            null
        } catch (error: RuntimeException) {
            reportOnce(paperFailureReported, "Paper stack rarity read failed", error)
            null
        }
    }

    private fun reportOnce(
        flag: AtomicBoolean,
        message: String,
        error: Throwable,
    ) {
        if (flag.compareAndSet(false, true)) {
            val type = (error as? InvocationTargetException)?.targetException?.javaClass?.simpleName ?: error.javaClass.simpleName
            warningSink.warn("$message ($type); using compatibility fallback")
        }
    }

    private fun mapRarity(value: Any?): MinecraftItemRarity? =
        value
            ?.toString()
            ?.uppercase()
            ?.let { name -> MinecraftItemRarity.entries.firstOrNull { it.name == name } }

    private fun MinecraftItemRarity.whenEnchanted(): MinecraftItemRarity =
        when (this) {
            MinecraftItemRarity.COMMON,
            MinecraftItemRarity.UNCOMMON,
            -> MinecraftItemRarity.RARE
            MinecraftItemRarity.RARE,
            MinecraftItemRarity.EPIC,
            -> MinecraftItemRarity.EPIC
        }
}

private val modernRarityCapabilityByClass =
    RuntimeClassCapabilityCache<ModernRarityCapability>(::resolveModernRarityCapability)

private fun resolveModernRarityCapability(type: Class<*>): ModernRarityCapability =
    try {
        val hasRarity = type.methods.firstOrNull { it.name == HAS_RARITY_METHOD && it.parameterCount == 0 }
        val getRarity = type.methods.firstOrNull { it.name == GET_RARITY_METHOD && it.parameterCount == 0 }
        if (hasRarity == null || getRarity == null) {
            ModernRarityCapability.Unsupported
        } else {
            ModernRarityCapability.Supported(hasRarity, getRarity)
        }
    } catch (_: SecurityException) {
        ModernRarityCapability.Unsupported
    } catch (_: LinkageError) {
        ModernRarityCapability.Unsupported
    }

private val paperRarityCapabilityByClass =
    RuntimeClassCapabilityCache<PaperRarityCapability>(::resolvePaperRarityCapability)

private fun resolvePaperRarityCapability(type: Class<*>): PaperRarityCapability =
    try {
        type.methods
            .firstOrNull {
                it.name == PAPER_STACK_RARITY_METHOD &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(ItemStack::class.java)
            }?.let(PaperRarityCapability::Supported)
            ?: PaperRarityCapability.Unsupported
    } catch (_: SecurityException) {
        PaperRarityCapability.Unsupported
    } catch (_: LinkageError) {
        PaperRarityCapability.Unsupported
    }

private sealed interface ModernRarityCapability {
    data class Supported(
        val hasRarity: Method,
        val getRarity: Method,
    ) : ModernRarityCapability

    data object Unsupported : ModernRarityCapability
}

private sealed interface PaperRarityCapability {
    data class Supported(
        val method: Method,
    ) : PaperRarityCapability

    data object Unsupported : PaperRarityCapability
}

private const val HAS_RARITY_METHOD = "hasRarity"
private const val GET_RARITY_METHOD = "getRarity"
private const val PAPER_STACK_RARITY_METHOD = "getItemStackRarity"
