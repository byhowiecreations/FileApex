package com.fileapex.ui.explorer

/** Which floating buttons the explorer shows for the current selection, whatever chrome the shell draws around it. */
enum class ExplorerFabMode {
    None,

    /** Copy and Send To, in the files browser. */
    Copy,

    /** One Send button while the user picks files for a friend. */
    Pick;

    companion object {
        fun of(picking: Boolean, selecting: Boolean, selectedCount: Int, multiCopying: Boolean): ExplorerFabMode = when {
            !selecting || selectedCount == 0 -> None
            picking -> Pick
            multiCopying -> None
            else -> Copy
        }
    }
}
