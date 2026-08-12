package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.inventory.ItemStack
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

public class BukkitItemTranslationKeyResolver {
    public fun resolve(stack: ItemStack): String {
        val material = stack.type
        val fallback =
            if (material.isBlock) {
                "block.minecraft.${material.key.key}"
            } else {
                "item.minecraft.${material.key.key}"
            }
        return resolveRuntimeTranslationKey(stack, fallback)
    }
}

internal fun resolveRuntimeTranslationKey(
    stack: Any,
    fallback: String,
): String {
    for (method in translationMethodsByClass.get(stack.javaClass)) {
        val value = invokeStringMethod(stack, method)
        if (value != null && minecraftTranslationKey.matches(value)) {
            return value
        }
    }
    return fallback
}

private fun invokeStringMethod(
    target: Any,
    method: Method,
): String? =
    try {
        method.invoke(target) as? String
    } catch (_: IllegalAccessException) {
        null
    } catch (_: InvocationTargetException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: LinkageError) {
        null
    }

private val translationMethodsByClass =
    RuntimeClassCapabilityCache<List<Method>> { type ->
        runtimeTranslationMethodNames.mapNotNull { methodName ->
            try {
                type.getMethod(methodName)
            } catch (_: NoSuchMethodException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
    }

private val runtimeTranslationMethodNames = listOf("translationKey", "getTranslationKey")
private val minecraftTranslationKey = Regex("(?:block|item)\\.minecraft\\.[a-z0-9_.-]{1,220}")
