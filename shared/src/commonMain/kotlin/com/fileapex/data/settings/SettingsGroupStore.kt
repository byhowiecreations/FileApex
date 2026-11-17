package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which Settings sections the user has folded away. Sections are named by id so a new one needs no new setting. */
class SettingsGroupStore(private val store: SettingsKvStore) {
    private val collapsedFlow = MutableStateFlow(
        store.keys(PREFIX).filter { !store.getBoolean(it, true) }.map { it.removePrefix(PREFIX) }.toSet()
    )

    val collapsed: StateFlow<Set<String>> = collapsedFlow.asStateFlow()

    fun setExpanded(sectionId: String, expanded: Boolean) {
        if (expanded) store.remove(PREFIX + sectionId) else store.putBoolean(PREFIX + sectionId, false)
        collapsedFlow.value = if (expanded) collapsedFlow.value - sectionId else collapsedFlow.value + sectionId
    }

    fun reset() {
        store.keys(PREFIX).forEach(store::remove)
        collapsedFlow.value = emptySet()
    }

    private companion object {
        const val PREFIX = "settings_group_v2_"
    }
}
