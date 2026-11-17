package com.fileapex.ui.transfer

enum class TransferRowState {
    Queued, Preparing, Connecting, Waiting, Verifying, Active, Paused, Done, Failed, Refused, Canceled
}

enum class TransferRowDensity { Compact, Standard, Inline }

enum class TransferStatusFilter(val labelKey: String) {
    All("transfer_filter_all"),
    Active("transfer_filter_active"),
    Waiting("transfer_filter_waiting"),
    Failed("transfer_filter_failed"),
    Done("transfer_filter_done");

    fun matches(state: TransferRowState): Boolean = when (this) {
        All -> true
        Active -> state == TransferRowState.Active || state == TransferRowState.Preparing ||
            state == TransferRowState.Connecting || state == TransferRowState.Verifying
        Waiting -> state == TransferRowState.Queued || state == TransferRowState.Waiting || state == TransferRowState.Paused
        Failed -> state == TransferRowState.Failed || state == TransferRowState.Refused || state == TransferRowState.Canceled
        Done -> state == TransferRowState.Done
    }
}

/** What one transfer row shows, whatever the source: the live batch, a queue row, a friend transfer or a finished send. */
data class TransferRowModel(
    val id: String,
    val title: String,
    val peer: String,
    val state: TransferRowState,
    /** 0 to 1, or null when the size is not known yet. */
    val progress: Float?,
    val statusText: String,
    val error: String?,
    val warning: String?,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canRemove: Boolean,
    val isSend: Boolean
) {
    val working: Boolean
        get() = state == TransferRowState.Active || state == TransferRowState.Preparing ||
            state == TransferRowState.Connecting || state == TransferRowState.Verifying
}

object TransferStatusFilters {
    fun apply(models: List<TransferRowModel>, filter: TransferStatusFilter): List<TransferRowModel> =
        models.filter { filter.matches(it.state) }
}
