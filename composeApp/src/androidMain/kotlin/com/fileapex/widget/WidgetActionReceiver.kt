package com.fileapex.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fileapex.data.bulletin.BulletinChecklists
import com.fileapex.domain.widget.WidgetActionCatalog
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetTargetBindingCodec
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_RUN_WIDGET_ACTION -> handleRunAction(appContext, intent)
            ACTION_LIST_ITEM_CLICK -> {
                if (intent.hasExtra(EXTRA_CHECKLIST_ITEM_ID)) {
                    handleToggleChecklist(appContext, intent)
                } else {
                    handleRunAction(appContext, intent)
                }
            }
        }
    }

    private fun handleRunAction(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(WidgetDeepLinks.EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val target = WidgetActionCatalog.byWireId(intent.getStringExtra(WidgetDeepLinks.EXTRA_ACTION_WIRE_ID))
            ?: WidgetActionTarget.OPEN_BULLETIN
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            target != WidgetActionTarget.OPEN_BULLETIN &&
            target != WidgetActionTarget.POST_TO_BOARD &&
            !WidgetConfigStore(context).hasConfig(appWidgetId)
        ) {
            val configureIntent = Intent(context, WidgetConfigureActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(configureIntent)
            return
        }
        val binding = WidgetTargetBindingCodec.decode(intent.getStringExtra(WidgetDeepLinks.EXTRA_BINDING_WIRE))
        val pendingResult = goAsync()
        scope.launch {
            try {
                val result = AndroidWidgetActionDispatcher(LiveWidgetActionDeps(context))
                    .dispatch(target, binding)
                if (result is WidgetDispatchResult.Message) {
                    BriefToast.show(result.text)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleToggleChecklist(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_CHECKLIST_ITEM_ID)?.trim().orEmpty()
        if (itemId.isEmpty()) return
        val nextDone = intent.getBooleanExtra(EXTRA_CHECKLIST_NEXT_DONE, false)
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (FileApexAndroidBootstrap.ensureInitialized(context)) {
                    BulletinChecklists.setDone(itemId, nextDone)
                    WidgetUiFormatter.updateBulletinWidgets(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RUN_WIDGET_ACTION = "com.fileapex.widget.ACTION_RUN"
        const val ACTION_LIST_ITEM_CLICK = "com.fileapex.widget.ACTION_LIST_ITEM_CLICK"
        const val EXTRA_CHECKLIST_ITEM_ID = "com.fileapex.extra.CHECKLIST_ITEM_ID"
        const val EXTRA_CHECKLIST_NEXT_DONE = "com.fileapex.extra.CHECKLIST_NEXT_DONE"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
