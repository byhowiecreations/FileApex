package com.fileapex.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

sealed class BaseFileApexWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> WidgetUiFormatter.updateWidgetById(context, id) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetUiFormatter.updateWidgetById(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetConfigStore(context)
        appWidgetIds.forEach { id -> store.remove(id) }
    }
}

class QuickActionsWidgetProvider : BaseFileApexWidgetProvider()
class DeviceCardWidgetProvider : BaseFileApexWidgetProvider()
class DropTargetWidgetProvider : BaseFileApexWidgetProvider()
class BulletinBoardWidgetProvider : BaseFileApexWidgetProvider()
