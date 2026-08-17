package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import org.bukkit.entity.Item
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

public sealed interface NativeItemOwnerResolution {
    public data class Found(
        public val ownerUuid: UUID,
    ) : NativeItemOwnerResolution

    public data object Missing : NativeItemOwnerResolution

    public data object Unsupported : NativeItemOwnerResolution

    public data class Failed(
        public val cause: Throwable,
    ) : NativeItemOwnerResolution
}

public fun interface BukkitNativeItemOwnerResolver {
    public fun resolve(item: Item): NativeItemOwnerResolution
}

public object ReflectiveBukkitNativeItemOwnerResolver : BukkitNativeItemOwnerResolver {
    private val accessors =
        object : ClassValue<OwnerAccessor>() {
            override fun computeValue(type: Class<*>): OwnerAccessor =
                type.methods
                    .firstOrNull { method ->
                        method.name == "getOwner" &&
                            method.parameterCount == 0 &&
                            method.returnType == UUID::class.java
                    }?.let(::SupportedOwnerAccessor)
                    ?: UnsupportedOwnerAccessor
        }

    override fun resolve(item: Item): NativeItemOwnerResolution =
        try {
            accessors.get(item.javaClass).resolve(item)
        } catch (error: SecurityException) {
            NativeItemOwnerResolution.Failed(error)
        } catch (error: LinkageError) {
            NativeItemOwnerResolution.Failed(error)
        }

    private fun interface OwnerAccessor {
        fun resolve(item: Item): NativeItemOwnerResolution
    }

    private class SupportedOwnerAccessor(
        private val method: Method,
    ) : OwnerAccessor {
        override fun resolve(item: Item): NativeItemOwnerResolution =
            try {
                (method.invoke(item) as? UUID)
                    ?.let(NativeItemOwnerResolution::Found)
                    ?: NativeItemOwnerResolution.Missing
            } catch (error: InvocationTargetException) {
                NativeItemOwnerResolution.Failed(error.cause ?: error)
            } catch (error: IllegalAccessException) {
                NativeItemOwnerResolution.Failed(error)
            } catch (error: IllegalArgumentException) {
                NativeItemOwnerResolution.Failed(error)
            }
    }

    private data object UnsupportedOwnerAccessor : OwnerAccessor {
        override fun resolve(item: Item): NativeItemOwnerResolution = NativeItemOwnerResolution.Unsupported
    }
}

public class BukkitNativeItemOwnershipReconciler(
    private val assignmentService: ItemOwnershipAssignmentService,
    private val warningSink: DisplayWarningSink,
    private val ownerResolver: BukkitNativeItemOwnerResolver = ReflectiveBukkitNativeItemOwnerResolver,
) {
    public fun reconcile(item: Item) {
        if (!item.isValid || !item.isItemDropLifecycleEligible()) return
        when (val resolution = ownerResolver.resolve(item)) {
            is NativeItemOwnerResolution.Found -> assign(item, resolution.ownerUuid)
            NativeItemOwnerResolution.Missing,
            NativeItemOwnerResolution.Unsupported,
            -> Unit
            is NativeItemOwnerResolution.Failed ->
                warningSink.warn(
                    "native Item owner lookup failed (${resolution.cause.javaClass.simpleName})",
                    RuntimeDiagnosticContext(cause = resolution.cause),
                )
        }
    }

    private fun assign(
        item: Item,
        ownerUuid: UUID,
    ) {
        when (
            val outcome =
                assignmentService.assignIfUnowned(
                    ItemOwnershipAssignmentRequest(item.uniqueId, item.world.name, listOf(ownerUuid)),
                )
        ) {
            is ItemOwnershipAssignmentOutcome.Assigned -> Unit
            is ItemOwnershipAssignmentOutcome.Ignored -> Unit
            is ItemOwnershipAssignmentOutcome.Rejected ->
                warningSink.warn(
                    "native Item ownership import rejected",
                    RuntimeDiagnosticContext(fields = mapOf("reason" to outcome.reason)),
                )
            is ItemOwnershipAssignmentOutcome.Failed ->
                warningSink.warn(
                    "native Item ownership import failed",
                    RuntimeDiagnosticContext(fields = mapOf("errorType" to outcome.errorType)),
                )
        }
    }
}
