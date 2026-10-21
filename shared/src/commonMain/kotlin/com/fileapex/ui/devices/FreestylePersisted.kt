package com.fileapex.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices

internal class FreestylePersisted(
    val freestyleMode: FreestyleLayoutMode,
    val editTutorialShown: Boolean,
    val persistedCardOptionsX: Float?,
    val persistedCardOptionsY: Float?,
    val persistedCardVerticalOptionsX: Float?,
    val persistedCardVerticalOptionsY: Float?,
    val persistedTileOptionsX: Float?,
    val persistedTileOptionsY: Float?,
    val persistedCardNodeOffsets: Map<String, Pair<Float, Float>>,
    val persistedCardVerticalNodeOffsets: Map<String, Pair<Float, Float>>,
    val persistedTileNodeOffsets: Map<String, Pair<Float, Float>>,
    val persistedCardMenuOrders: Map<String, String>,
    val persistedCardVerticalMenuOrders: Map<String, String>,
    val persistedTileMenuOrders: Map<String, String>,
    val persistedOptionsMenuOrder: String,
    val persistedCardPinnedActions: Map<String, Pair<Float, Float>>,
    val persistedCardVerticalPinnedActions: Map<String, Pair<Float, Float>>,
    val persistedTilePinnedActions: Map<String, Pair<Float, Float>>,
    val currentPinnedActionsMap: Map<String, Pair<Float, Float>>
)

@Composable
internal fun rememberFreestylePersisted(): FreestylePersisted {
    val freestyleMode by FileApexServices.settings.freestyleLayoutMode.collectAsState()
    val editTutorialShown by FileApexServices.settings.freestyleEditTutorialShown.collectAsState()
    val persistedCardOptionsX by FileApexServices.settings.freestyleCardOptionsPosX.collectAsState()
    val persistedCardOptionsY by FileApexServices.settings.freestyleCardOptionsPosY.collectAsState()
    val persistedCardVerticalOptionsX by FileApexServices.settings.freestyleCardVerticalOptionsPosX.collectAsState()
    val persistedCardVerticalOptionsY by FileApexServices.settings.freestyleCardVerticalOptionsPosY.collectAsState()
    val persistedTileOptionsX by FileApexServices.settings.freestyleTileOptionsPosX.collectAsState()
    val persistedTileOptionsY by FileApexServices.settings.freestyleTileOptionsPosY.collectAsState()
    val persistedCardNodeOffsets by FileApexServices.settings.freestyleCardNodeOffsets.collectAsState()
    val persistedCardVerticalNodeOffsets by FileApexServices.settings.freestyleCardVerticalNodeOffsets.collectAsState()
    val persistedTileNodeOffsets by FileApexServices.settings.freestyleTileNodeOffsets.collectAsState()
    val persistedCardMenuOrders by FileApexServices.settings.freestyleCardMenuOrders.collectAsState()
    val persistedCardVerticalMenuOrders by FileApexServices.settings.freestyleCardVerticalMenuOrders.collectAsState()
    val persistedTileMenuOrders by FileApexServices.settings.freestyleTileMenuOrders.collectAsState()
    val persistedOptionsMenuOrder by FileApexServices.settings.freestyleOptionsMenuOrder.collectAsState()
    val persistedCardPinnedActions by FileApexServices.settings.freestyleCardPinnedActions.collectAsState()
    val persistedCardVerticalPinnedActions by FileApexServices.settings.freestyleCardVerticalPinnedActions.collectAsState()
    val persistedTilePinnedActions by FileApexServices.settings.freestyleTilePinnedActions.collectAsState()
    val currentPinnedActionsMap = when (freestyleMode) {
        FreestyleLayoutMode.CARDS_HORIZONTAL -> persistedCardPinnedActions
        FreestyleLayoutMode.CARDS_VERTICAL -> persistedCardVerticalPinnedActions
        FreestyleLayoutMode.TILES -> persistedTilePinnedActions
    }
    return FreestylePersisted(
        freestyleMode = freestyleMode,
        editTutorialShown = editTutorialShown,
        persistedCardOptionsX = persistedCardOptionsX,
        persistedCardOptionsY = persistedCardOptionsY,
        persistedCardVerticalOptionsX = persistedCardVerticalOptionsX,
        persistedCardVerticalOptionsY = persistedCardVerticalOptionsY,
        persistedTileOptionsX = persistedTileOptionsX,
        persistedTileOptionsY = persistedTileOptionsY,
        persistedCardNodeOffsets = persistedCardNodeOffsets,
        persistedCardVerticalNodeOffsets = persistedCardVerticalNodeOffsets,
        persistedTileNodeOffsets = persistedTileNodeOffsets,
        persistedCardMenuOrders = persistedCardMenuOrders,
        persistedCardVerticalMenuOrders = persistedCardVerticalMenuOrders,
        persistedTileMenuOrders = persistedTileMenuOrders,
        persistedOptionsMenuOrder = persistedOptionsMenuOrder,
        persistedCardPinnedActions = persistedCardPinnedActions,
        persistedCardVerticalPinnedActions = persistedCardVerticalPinnedActions,
        persistedTilePinnedActions = persistedTilePinnedActions,
        currentPinnedActionsMap = currentPinnedActionsMap
    )
}
