package com.github.command1264.itemdropv2.core

/**
 * 標示僅供 ItemDropV2 模組間協作的介面。
 *
 * 這些型別不是穩定的第三方 public API，版本更新時可能不相容。
 */
@RequiresOptIn(
    message = "This contract is internal to ItemDropV2 and may change without notice.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class InternalItemDropApi
