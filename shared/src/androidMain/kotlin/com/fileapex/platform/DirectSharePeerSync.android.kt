package com.fileapex.platform

internal actual fun syncDirectShareTargetsFromPeers() {
    DirectShareShortcutCoordinator.refreshFromPeerDiscovery()
}
