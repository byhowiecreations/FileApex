package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

typealias FreestyleOffsets = Map<String, Pair<Float, Float>>

/** Where the user put things in the Freestyle theme, per layout (horizontal cards, vertical cards, tiles). */
class FreestyleLayoutStore(private val store: SettingsKvStore) {
    private class Keys(
        val optionsX: String,
        val optionsY: String,
        val nodePrefix: String,
        val menuOrders: String,
        val menuOrdersFallback: String?,
        val pinned: String,
        val pinnedFallback: String?,
    )

    private val keys = mapOf(
        FreestyleLayoutMode.CARDS_HORIZONTAL to Keys(
            BaseAppSettings.KEY_FREESTYLE_CARD_OPTIONS_X, BaseAppSettings.KEY_FREESTYLE_CARD_OPTIONS_Y, "fcn:",
            BaseAppSettings.KEY_FREESTYLE_CARD_MENU_ORDERS, null,
            BaseAppSettings.KEY_FREESTYLE_CARD_PINNED_ACTIONS, null
        ),
        FreestyleLayoutMode.CARDS_VERTICAL to Keys(
            BaseAppSettings.KEY_FREESTYLE_CARD_VERTICAL_OPTIONS_X, BaseAppSettings.KEY_FREESTYLE_CARD_VERTICAL_OPTIONS_Y, "fvn:",
            BaseAppSettings.KEY_FREESTYLE_CARD_VERTICAL_MENU_ORDERS, BaseAppSettings.KEY_FREESTYLE_CARD_MENU_ORDERS,
            BaseAppSettings.KEY_FREESTYLE_CARD_VERTICAL_PINNED_ACTIONS, BaseAppSettings.KEY_FREESTYLE_CARD_PINNED_ACTIONS
        ),
        FreestyleLayoutMode.TILES to Keys(
            BaseAppSettings.KEY_FREESTYLE_TILE_OPTIONS_X, BaseAppSettings.KEY_FREESTYLE_TILE_OPTIONS_Y, "ftn:",
            BaseAppSettings.KEY_FREESTYLE_TILE_MENU_ORDERS, null,
            BaseAppSettings.KEY_FREESTYLE_TILE_PINNED_ACTIONS, null
        ),
    )

    private val optionsX = keys.mapValues { (mode, k) ->
        MutableStateFlow(
            store.getString(k.optionsX, "").toFloatOrNull()
                ?: if (mode == FreestyleLayoutMode.CARDS_VERTICAL) store.getString(BaseAppSettings.KEY_FREESTYLE_CARD_OPTIONS_X, "").toFloatOrNull() else null
        )
    }
    private val optionsY = keys.mapValues { (mode, k) ->
        MutableStateFlow(
            store.getString(k.optionsY, "").toFloatOrNull()
                ?: if (mode == FreestyleLayoutMode.CARDS_VERTICAL) store.getString(BaseAppSettings.KEY_FREESTYLE_CARD_OPTIONS_Y, "").toFloatOrNull() else null
        )
    }
    private val positions = keys.mapValues { (_, k) -> DevicePositionTable(store, k.nodePrefix, emptyList(), emptyList()) }

    private val menuOrders = keys.mapValues { (_, k) ->
        MutableStateFlow(
            decodeStringMap(store.getString(k.menuOrders, "")).ifEmpty {
                k.menuOrdersFallback?.let { decodeStringMap(store.getString(it, "")) }.orEmpty()
            }
        )
    }
    private val pinned = keys.mapValues { (_, k) ->
        MutableStateFlow(
            decodeKineticOffsets(store.getString(k.pinned, "")).ifEmpty {
                k.pinnedFallback?.let { decodeKineticOffsets(store.getString(it, "")) }.orEmpty()
            }
        )
    }
    private val layoutMode = MutableStateFlow(
        FreestyleLayoutMode.fromStorage(store.getString(BaseAppSettings.KEY_FREESTYLE_LAYOUT_MODE, FreestyleLayoutMode.DEFAULT.storageKey))
    )
    private val editTutorialShown = MutableStateFlow(store.getBoolean(BaseAppSettings.KEY_FREESTYLE_EDIT_TUTORIAL_SHOWN, false))
    private val optionsMenuOrder = MutableStateFlow(store.getString(BaseAppSettings.KEY_FREESTYLE_OPTIONS_MENU_ORDER, DEFAULT_OPTIONS_MENU_ORDER))

    val layoutModeFlow: StateFlow<FreestyleLayoutMode> = layoutMode.asStateFlow()
    val editTutorialShownFlow: StateFlow<Boolean> = editTutorialShown.asStateFlow()
    val optionsMenuOrderFlow: StateFlow<String> = optionsMenuOrder.asStateFlow()

    fun optionsPosX(mode: FreestyleLayoutMode): StateFlow<Float?> = optionsX.getValue(mode).asStateFlow()
    fun optionsPosY(mode: FreestyleLayoutMode): StateFlow<Float?> = optionsY.getValue(mode).asStateFlow()
    fun nodeOffsets(mode: FreestyleLayoutMode): StateFlow<FreestyleOffsets> = positions.getValue(mode).flow
    fun menuOrders(mode: FreestyleLayoutMode): StateFlow<Map<String, String>> = menuOrders.getValue(mode).asStateFlow()
    fun pinnedActions(mode: FreestyleLayoutMode): StateFlow<FreestyleOffsets> = pinned.getValue(mode).asStateFlow()

    fun setOptionsPosition(mode: FreestyleLayoutMode, x: Float?, y: Float?) {
        val k = keys.getValue(mode)
        store.putString(k.optionsX, x?.toString().orEmpty())
        store.putString(k.optionsY, y?.toString().orEmpty())
        optionsX.getValue(mode).value = x
        optionsY.getValue(mode).value = y
    }

    fun setNodeOffset(mode: FreestyleLayoutMode, deviceId: String, x: Float, y: Float) {
        positions.getValue(mode).put(mapOf(deviceId to Pair(x, y)), persist = true)
    }

    fun setMenuOrder(mode: FreestyleLayoutMode, deviceId: String, order: String) {
        val flow = menuOrders.getValue(mode)
        val updated = flow.value + (deviceId to order)
        flow.value = updated
        store.putString(keys.getValue(mode).menuOrders, encodeStringMap(updated))
    }

    fun setPinnedActionOffset(mode: FreestyleLayoutMode, actionKey: String, x: Float, y: Float) =
        writePinned(mode, pinned.getValue(mode).value + (actionKey to Pair(x, y)))

    fun removePinnedAction(mode: FreestyleLayoutMode, actionKey: String) =
        writePinned(mode, pinned.getValue(mode).value - actionKey)

    fun resetPinnedActions(mode: FreestyleLayoutMode) {
        pinned.getValue(mode).value = emptyMap()
        store.putString(keys.getValue(mode).pinned, "")
    }

    private fun writePinned(mode: FreestyleLayoutMode, updated: FreestyleOffsets) {
        pinned.getValue(mode).value = updated
        store.putString(
            keys.getValue(mode).pinned,
            updated.entries.joinToString(";") { "${it.key}|${it.value.first}|${it.value.second}" }
        )
    }

    fun setLayoutMode(mode: FreestyleLayoutMode) {
        store.putString(BaseAppSettings.KEY_FREESTYLE_LAYOUT_MODE, mode.storageKey)
        layoutMode.value = mode
    }

    fun setEditTutorialShown(shown: Boolean) {
        store.putBoolean(BaseAppSettings.KEY_FREESTYLE_EDIT_TUTORIAL_SHOWN, shown)
        editTutorialShown.value = shown
    }

    fun setOptionsMenuOrder(order: String) {
        store.putString(BaseAppSettings.KEY_FREESTYLE_OPTIONS_MENU_ORDER, order)
        optionsMenuOrder.value = order
    }

    /** The settings store has been cleared; bring the in-memory state back to the defaults. */
    fun forgetInMemory() {
        optionsX.values.forEach { it.value = null }
        optionsY.values.forEach { it.value = null }
        layoutMode.value = FreestyleLayoutMode.DEFAULT
        editTutorialShown.value = false
        positions.values.forEach { it.forgetInMemory() }
        menuOrders.values.forEach { it.value = emptyMap() }
        pinned.values.forEach { it.value = emptyMap() }
        optionsMenuOrder.value = DEFAULT_OPTIONS_MENU_ORDER
    }

    private companion object {
        const val DEFAULT_OPTIONS_MENU_ORDER = "files,add_device,join_device,send_clipboard,check_batteries,settings"
    }
}
