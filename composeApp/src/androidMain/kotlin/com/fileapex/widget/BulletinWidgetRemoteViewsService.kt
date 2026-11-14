package com.fileapex.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.fileapex.R
import com.fileapex.di.FileApexServices
import com.fileapex.domain.widget.BulletinWidgetItem
import com.fileapex.domain.widget.BulletinWidgetSnapshotBuilder
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.platform.FileApexAndroidBootstrap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

class BulletinWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        BulletinRemoteViewsFactory(applicationContext)
}

private class BulletinRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<BulletinWidgetItem> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items = if (FileApexAndroidBootstrap.ensureInitialized(context)) {
            val notes = runBlocking { FileApexServices.bulletinBoardRepository.observeAsNotes().first() }
            BulletinWidgetSnapshotBuilder.build(
                notes = notes,
                maxItems = BulletinWidgetSnapshotBuilder.DEFAULT_MAX_ITEMS,
                zoneId = ZoneId.systemDefault().id
            ).items
        } else {
            emptyList()
        }
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_bulletin_row)
        val item = items.getOrNull(position) ?: return row

        row.setTextViewText(R.id.widget_row_author, item.authorLabel)
        row.setTextViewText(R.id.widget_row_time, item.timeLabel)

        val openFillIn = Intent().apply {
            putExtra(WidgetDeepLinks.EXTRA_ACTION_WIRE_ID, WidgetActionTarget.OPEN_BULLETIN.wireId)
        }
        row.setOnClickFillInIntent(R.id.widget_bulletin_row_root, openFillIn)

        if (item.isChecklist) {
            row.setViewVisibility(R.id.widget_row_preview, if (item.checklistTitle.isNotBlank()) View.VISIBLE else View.GONE)
            row.setTextViewText(R.id.widget_row_preview, item.checklistTitle)
            row.setViewVisibility(R.id.widget_row_checklist_container, View.VISIBLE)
            bindChecklistLines(row, item)
        } else {
            row.setViewVisibility(R.id.widget_row_preview, View.VISIBLE)
            row.setTextViewText(R.id.widget_row_preview, item.previewText)
            row.setViewVisibility(R.id.widget_row_checklist_container, View.GONE)
        }
        return row
    }

    private fun bindChecklistLines(row: RemoteViews, item: BulletinWidgetItem) {
        val slots = listOf(
            Triple(R.id.widget_chk_row_0, R.id.widget_chk_icon_0, R.id.widget_chk_text_0),
            Triple(R.id.widget_chk_row_1, R.id.widget_chk_icon_1, R.id.widget_chk_text_1),
            Triple(R.id.widget_chk_row_2, R.id.widget_chk_icon_2, R.id.widget_chk_text_2),
            Triple(R.id.widget_chk_row_3, R.id.widget_chk_icon_3, R.id.widget_chk_text_3)
        )
        for (i in slots.indices) {
            val (containerId, iconId, textId) = slots[i]
            val line = item.checklistItems.getOrNull(i)
            if (line == null) {
                row.setViewVisibility(containerId, View.GONE)
                continue
            }
            row.setViewVisibility(containerId, View.VISIBLE)
            row.setImageViewResource(
                iconId,
                if (line.done) R.drawable.ic_widget_check_on else R.drawable.ic_widget_check_off
            )
            row.setTextViewText(textId, line.text)
            val toggleFillIn = Intent().apply {
                putExtra(WidgetActionReceiver.EXTRA_CHECKLIST_ITEM_ID, line.id)
                putExtra(WidgetActionReceiver.EXTRA_CHECKLIST_NEXT_DONE, !line.done)
            }
            row.setOnClickFillInIntent(containerId, toggleFillIn)
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.noteId?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
