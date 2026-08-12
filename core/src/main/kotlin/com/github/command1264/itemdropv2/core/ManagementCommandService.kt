package com.github.command1264.itemdropv2.core

public interface ItemDisplaySettingsManager : ItemDisplaySettingsRepository {
    public fun setEnabled(enabled: Boolean): ItemDisplaySettingsUpdateResult

    public fun reload(): ItemDisplaySettingsUpdateResult
}

public sealed interface ItemDisplaySettingsUpdateResult {
    public data class Applied(
        public val settings: ItemDisplaySettings,
    ) : ItemDisplaySettingsUpdateResult

    public data class Failed(
        public val reason: String,
    ) : ItemDisplaySettingsUpdateResult
}

public fun interface LoadedItemRefreshView {
    public fun refresh(settings: ItemDisplaySettings)
}

public sealed interface ManagementCommandOutcome {
    public data object Enabled : ManagementCommandOutcome

    public data object Disabled : ManagementCommandOutcome

    public data object AlreadyEnabled : ManagementCommandOutcome

    public data object AlreadyDisabled : ManagementCommandOutcome

    public data object Reloaded : ManagementCommandOutcome

    public data class Failed(
        public val reason: String,
    ) : ManagementCommandOutcome
}

public class ManagementCommandService(
    private val settingsManager: ItemDisplaySettingsManager,
    private val refreshView: LoadedItemRefreshView,
) {
    public fun toggle(requestedState: Boolean?): ManagementCommandOutcome {
        val current = settingsManager.settings().enabled
        val target = requestedState ?: !current
        if (target == current) {
            return if (current) ManagementCommandOutcome.AlreadyEnabled else ManagementCommandOutcome.AlreadyDisabled
        }
        return when (val result = settingsManager.setEnabled(target)) {
            is ItemDisplaySettingsUpdateResult.Applied -> {
                refreshView.refresh(result.settings)
                if (target) ManagementCommandOutcome.Enabled else ManagementCommandOutcome.Disabled
            }
            is ItemDisplaySettingsUpdateResult.Failed -> ManagementCommandOutcome.Failed(result.reason)
        }
    }

    public fun reload(): ManagementCommandOutcome =
        when (val result = settingsManager.reload()) {
            is ItemDisplaySettingsUpdateResult.Applied -> {
                refreshView.refresh(result.settings)
                ManagementCommandOutcome.Reloaded
            }
            is ItemDisplaySettingsUpdateResult.Failed -> ManagementCommandOutcome.Failed(result.reason)
        }
}
