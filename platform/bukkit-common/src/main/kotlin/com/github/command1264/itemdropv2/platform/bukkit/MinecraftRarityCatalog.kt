package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftItemRarity

/**
 * Spigot 沒有公開 base rarity API 時的 vanilla fallback。
 *
 * 使用 Material 名稱規則與各次 rarity 變更的最小差異集合；不依賴 NMS，也不逐一
 * 列舉 Common 物品。Paper 仍優先使用 runtime API，以保留資料組件的 stack override。
 */
internal class MinecraftRarityCatalog {
    fun resolve(
        materialName: String,
        enchanted: Boolean,
        minecraftVersion: String,
    ): MinecraftItemRarity {
        val version = MinecraftRelease.parse(minecraftVersion)
        if (version == null || version !in MIN_SUPPORTED_RELEASE..MAX_VERIFIED_RELEASE) {
            return MinecraftItemRarity.COMMON
        }
        val normalizedName = materialName.uppercase()
        val base =
            if (version < RARITY_REWORK_RELEASE) {
                preReworkBaseRarity(normalizedName, version)
            } else {
                reworkedBaseRarity(normalizedName, version)
            }
        return if (enchanted) base.whenEnchanted() else base
    }

    private fun preReworkBaseRarity(
        materialName: String,
        version: MinecraftRelease,
    ): MinecraftItemRarity =
        trialRarity(materialName, version)
            ?: establishedBaseRarity(materialName, version)

    private fun trialRarity(
        materialName: String,
        version: MinecraftRelease,
    ): MinecraftItemRarity? {
        if (version < TRIALS_RELEASE) {
            return null
        }
        return when (materialName) {
            in TRIALS_EPIC_ITEMS -> MinecraftItemRarity.EPIC
            in TRIALS_RARE_ITEMS -> MinecraftItemRarity.RARE
            PIGLIN_BANNER_PATTERN -> MinecraftItemRarity.UNCOMMON
            else -> null
        }
    }

    private fun establishedBaseRarity(
        materialName: String,
        version: MinecraftRelease,
    ): MinecraftItemRarity =
        when {
            materialName.startsWith(MUSIC_DISC_PREFIX) -> MinecraftItemRarity.RARE
            materialName.endsWith(HEAD_SUFFIX) || materialName.endsWith(SKULL_SUFFIX) -> MinecraftItemRarity.UNCOMMON
            materialName == SPAWNER ->
                if (version in TECHNICAL_ITEMS_RELEASE..<SPAWNER_COMMON_RELEASE) {
                    MinecraftItemRarity.EPIC
                } else {
                    MinecraftItemRarity.COMMON
                }
            materialName in TECHNICAL_EPIC_ITEMS ->
                if (version >= TECHNICAL_ITEMS_RELEASE) MinecraftItemRarity.EPIC else MinecraftItemRarity.COMMON
            else -> ESTABLISHED_RARITIES[materialName] ?: MinecraftItemRarity.COMMON
        }

    private fun reworkedBaseRarity(
        materialName: String,
        version: MinecraftRelease,
    ): MinecraftItemRarity =
        when {
            materialName in REWORKED_EPIC_ITEMS -> MinecraftItemRarity.EPIC
            materialName in REWORKED_RARE_ITEMS -> MinecraftItemRarity.RARE
            materialName == ENCHANTED_BOOK ->
                if (version >= ENCHANTED_BOOK_RARE_RELEASE) {
                    MinecraftItemRarity.RARE
                } else {
                    MinecraftItemRarity.UNCOMMON
                }
            materialName in REWORKED_UNCOMMON_ITEMS -> MinecraftItemRarity.UNCOMMON
            materialName.startsWith(CHAINMAIL_PREFIX) -> MinecraftItemRarity.UNCOMMON
            materialName.endsWith(POTTERY_SHERD_SUFFIX) -> MinecraftItemRarity.UNCOMMON
            else -> MinecraftItemRarity.COMMON
        }

    private fun MinecraftItemRarity.whenEnchanted(): MinecraftItemRarity =
        when (this) {
            MinecraftItemRarity.COMMON,
            MinecraftItemRarity.UNCOMMON,
            -> MinecraftItemRarity.RARE
            MinecraftItemRarity.RARE,
            MinecraftItemRarity.EPIC,
            -> MinecraftItemRarity.EPIC
        }

    private companion object {
        private const val MUSIC_DISC_PREFIX = "MUSIC_DISC_"
        private const val HEAD_SUFFIX = "_HEAD"
        private const val SKULL_SUFFIX = "_SKULL"
        private const val CHAINMAIL_PREFIX = "CHAINMAIL_"
        private const val POTTERY_SHERD_SUFFIX = "_POTTERY_SHERD"
        private const val SPAWNER = "SPAWNER"
        private const val PIGLIN_BANNER_PATTERN = "PIGLIN_BANNER_PATTERN"
        private const val ENCHANTED_BOOK = "ENCHANTED_BOOK"

        private val MIN_SUPPORTED_RELEASE = MinecraftRelease(1, 14, 0)
        private val TECHNICAL_ITEMS_RELEASE = MinecraftRelease(1, 17, 0)
        private val SPAWNER_COMMON_RELEASE = MinecraftRelease(1, 19, 3)
        private val TRIALS_RELEASE = MinecraftRelease(1, 21, 0)
        private val RARITY_REWORK_RELEASE = MinecraftRelease(1, 21, 2)
        private val ENCHANTED_BOOK_RARE_RELEASE = MinecraftRelease(1, 21, 9)
        private val MAX_VERIFIED_RELEASE = MinecraftRelease(26, 2, 0)

        private data class MinecraftRelease(
            val major: Int,
            val minor: Int,
            val patch: Int,
        ) : Comparable<MinecraftRelease> {
            override fun compareTo(other: MinecraftRelease): Int =
                compareValuesBy(this, other, MinecraftRelease::major, MinecraftRelease::minor, MinecraftRelease::patch)

            companion object {
                private val RELEASE_PREFIX = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-|$)""")

                fun parse(value: String): MinecraftRelease? {
                    val match = RELEASE_PREFIX.find(value) ?: return null
                    val major = match.groupValues[1].toIntOrNull()
                    val minor = match.groupValues[2].toIntOrNull()
                    val patchText = match.groupValues[3]
                    val patch = if (patchText.isEmpty()) 0 else patchText.toIntOrNull()
                    return if (major != null && minor != null && patch != null) {
                        MinecraftRelease(major, minor, patch)
                    } else {
                        null
                    }
                }
            }
        }

        private val UNCOMMON_ESTABLISHED =
            setOf(
                "CREEPER_BANNER_PATTERN",
                "DRAGON_BREATH",
                "ELYTRA",
                "ENCHANTED_BOOK",
                "EXPERIENCE_BOTTLE",
                "HEART_OF_THE_SEA",
                "NETHER_STAR",
                "SKULL_BANNER_PATTERN",
                "TOTEM_OF_UNDYING",
            )

        private val RARE_ESTABLISHED =
            setOf(
                "BEACON",
                "CONDUIT",
                "END_CRYSTAL",
                "GOLDEN_APPLE",
            )

        private val EPIC_ESTABLISHED =
            setOf(
                "CHAIN_COMMAND_BLOCK",
                "COMMAND_BLOCK",
                "DRAGON_EGG",
                "ENCHANTED_GOLDEN_APPLE",
                "JIGSAW",
                "MOJANG_BANNER_PATTERN",
                "REPEATING_COMMAND_BLOCK",
                "STRUCTURE_BLOCK",
            )

        private val TECHNICAL_EPIC_ITEMS =
            setOf(
                "BARRIER",
                "COMMAND_BLOCK_MINECART",
                "DEBUG_STICK",
                "KNOWLEDGE_BOOK",
                "LIGHT",
                "STRUCTURE_VOID",
            )

        private val TRIALS_EPIC_ITEMS =
            setOf(
                "HEAVY_CORE",
                "MACE",
                "TRIDENT",
            )

        private val TRIALS_RARE_ITEMS =
            setOf(
                "FLOW_BANNER_PATTERN",
                "GUSTER_BANNER_PATTERN",
            )

        private val REWORKED_UNCOMMON_ITEMS =
            setOf(
                "CREEPER_BANNER_PATTERN",
                "PIGLIN_BANNER_PATTERN",
                "SNIFFER_EGG",
                "RECOVERY_COMPASS",
                "DISC_FRAGMENT_5",
                "NAUTILUS_SHELL",
                "ECHO_SHARD",
                "GOAT_HORN",
                "OMINOUS_BOTTLE",
                "NETHERITE_UPGRADE_SMITHING_TEMPLATE",
                "SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE",
                "DUNE_ARMOR_TRIM_SMITHING_TEMPLATE",
                "COAST_ARMOR_TRIM_SMITHING_TEMPLATE",
                "WILD_ARMOR_TRIM_SMITHING_TEMPLATE",
                "TIDE_ARMOR_TRIM_SMITHING_TEMPLATE",
                "SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE",
                "RIB_ARMOR_TRIM_SMITHING_TEMPLATE",
                "WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE",
                "SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE",
                "RAISER_ARMOR_TRIM_SMITHING_TEMPLATE",
                "HOST_ARMOR_TRIM_SMITHING_TEMPLATE",
                "FLOW_ARMOR_TRIM_SMITHING_TEMPLATE",
                "BOLT_ARMOR_TRIM_SMITHING_TEMPLATE",
                "MUSIC_DISC_13",
                "MUSIC_DISC_CAT",
                "MUSIC_DISC_BLOCKS",
                "MUSIC_DISC_CHIRP",
                "MUSIC_DISC_FAR",
                "MUSIC_DISC_MALL",
                "MUSIC_DISC_MELLOHI",
                "MUSIC_DISC_STAL",
                "MUSIC_DISC_STRAD",
                "MUSIC_DISC_WARD",
                "MUSIC_DISC_11",
                "MUSIC_DISC_WAIT",
                "MUSIC_DISC_5",
                "MUSIC_DISC_RELIC",
                "MUSIC_DISC_CREATOR_MUSIC_BOX",
                "MUSIC_DISC_PRECIPICE",
                "MUSIC_DISC_TEARS",
                "MUSIC_DISC_BOUNCE",
                "TOTEM_OF_UNDYING",
                "EXPERIENCE_BOTTLE",
                "CONDUIT",
                "HEART_OF_THE_SEA",
                "DRAGON_BREATH",
                "PLAYER_HEAD",
                "ZOMBIE_HEAD",
                "CREEPER_HEAD",
                "SKELETON_SKULL",
                "PIGLIN_HEAD",
            )

        private val REWORKED_RARE_ITEMS =
            setOf(
                "FLOW_BANNER_PATTERN",
                "GUSTER_BANNER_PATTERN",
                "ENCHANTED_GOLDEN_APPLE",
                "TRIDENT",
                "NETHER_STAR",
                "WARD_ARMOR_TRIM_SMITHING_TEMPLATE",
                "EYE_ARMOR_TRIM_SMITHING_TEMPLATE",
                "VEX_ARMOR_TRIM_SMITHING_TEMPLATE",
                "SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE",
                "WITHER_SKELETON_SKULL",
                "SKULL_BANNER_PATTERN",
                "MOJANG_BANNER_PATTERN",
                "MUSIC_DISC_PIGSTEP",
                "MUSIC_DISC_OTHERSIDE",
                "MUSIC_DISC_CREATOR",
                "MUSIC_DISC_LAVA_CHICKEN",
                "BEACON",
            )

        private val REWORKED_EPIC_ITEMS =
            setOf(
                "ELYTRA",
                "DRAGON_HEAD",
                "DRAGON_EGG",
                "SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE",
                "HEAVY_CORE",
                "MACE",
                "BARRIER",
                "CHAIN_COMMAND_BLOCK",
                "COMMAND_BLOCK",
                "REPEATING_COMMAND_BLOCK",
                "JIGSAW",
                "LIGHT",
                "COMMAND_BLOCK_MINECART",
                "STRUCTURE_BLOCK",
                "STRUCTURE_VOID",
                "DEBUG_STICK",
                "KNOWLEDGE_BOOK",
                "TEST_BLOCK",
                "TEST_INSTANCE_BLOCK",
            )

        private val ESTABLISHED_RARITIES =
            UNCOMMON_ESTABLISHED.associateWith { MinecraftItemRarity.UNCOMMON } +
                RARE_ESTABLISHED.associateWith { MinecraftItemRarity.RARE } +
                EPIC_ESTABLISHED.associateWith { MinecraftItemRarity.EPIC }
    }
}
