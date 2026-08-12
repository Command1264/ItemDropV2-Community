package com.github.command1264.itemdropv2.platform.view.community

import com.github.command1264.itemdropv2.core.BackendProvider
import com.github.command1264.itemdropv2.core.PresentationBackend
import com.github.command1264.itemdropv2.core.ServerFingerprint
import com.github.command1264.itemdropv2.platform.view.bukkit.BukkitPresentationBackendProvider

public class CommunityPresentationBackendProvider : BackendProvider {
    private val delegate = BukkitPresentationBackendProvider()

    override val id: String = "community-bukkit"
    override val artifactClassifier: String = "community"

    override fun incompatibilities(fingerprint: ServerFingerprint): List<String> = delegate.incompatibilities(fingerprint)

    override fun createBackend(fingerprint: ServerFingerprint): PresentationBackend = delegate.createBackend(fingerprint)
}
