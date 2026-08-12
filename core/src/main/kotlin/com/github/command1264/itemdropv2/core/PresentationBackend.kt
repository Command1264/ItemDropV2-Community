package com.github.command1264.itemdropv2.core

@InternalItemDropApi
public interface PresentationBackend :
    ItemPresentationView,
    AutoCloseable {
    public val id: String

    public fun activate()

    override fun close()
}

/**
 * 讓平台 adapter 在事件已直接提供目標物件時，不必再用 UUID 重新查找同一個目標。
 */
@InternalItemDropApi
public interface DirectItemPresentationView<T : Any> {
    public fun present(
        target: T,
        presentation: ItemPresentation,
    ): PresentationResult

    public fun clear(target: T): PresentationResult
}

/**
 * 讓平台 backend 依公開 API 呈現一次已提交的拾取回饋。
 *
 * 呼叫端必須在 [target] 仍存在時呼叫，避免原生拾取動畫找不到來源 Entity。
 * [carrierRemains] 表示同一個真實 Entity 在 transaction 後仍會存活。
 * [carrierResynchronizationSupported] 表示呼叫端能在平台動畫從 client 移除來源 Entity
 * 後重新同步該 carrier；backend 需要重新同步時回傳
 * [ItemPickupFeedbackResult.CarrierResynchronizationRequired]。
 */
@InternalItemDropApi
public interface DirectItemPickupFeedbackView<in A : Any, in T : Any> {
    public fun playPickupFeedback(
        actor: A,
        target: T,
        pickedUpAmount: Int,
        carrierRemains: Boolean,
        carrierResynchronizationSupported: Boolean,
    ): ItemPickupFeedbackResult
}

@InternalItemDropApi
public enum class ItemPickupFeedbackResult {
    Complete,
    CarrierResynchronizationRequired,
}

/**
 * 原版物品拾取音效使用兩次亂數取樣；平台 backend 共用此公式，避免不同 artifact 聽感分歧。
 */
@InternalItemDropApi
public fun vanillaItemPickupPitch(
    firstRandom: Float,
    secondRandom: Float,
): Float = (firstRandom - secondRandom) * VANILLA_PICKUP_PITCH_SPREAD + VANILLA_PICKUP_PITCH_BASE

private const val VANILLA_PICKUP_PITCH_SPREAD = 1.4F
private const val VANILLA_PICKUP_PITCH_BASE = 2.0F
