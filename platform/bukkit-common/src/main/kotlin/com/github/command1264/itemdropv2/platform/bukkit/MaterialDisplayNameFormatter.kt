package com.github.command1264.itemdropv2.platform.bukkit

import java.util.Locale

public object MaterialDisplayNameFormatter {
    private val materialNamePattern = Regex("[A-Z0-9]+(?:_[A-Z0-9]+)*")

    public fun format(materialName: String): String {
        require(materialNamePattern.matches(materialName)) { "invalid Material enum name" }
        return materialName
            .split('_')
            .joinToString(" ") { component ->
                val lower = component.lowercase(Locale.ROOT)
                lower.replaceFirstChar { character -> character.titlecase(Locale.ROOT) }
            }
    }
}
