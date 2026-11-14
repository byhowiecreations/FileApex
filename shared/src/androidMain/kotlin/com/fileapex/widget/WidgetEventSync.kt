package com.fileapex.widget

import android.content.Context
import com.fileapex.di.FileApexServices
import com.fileapex.domain.widget.BulletinWidgetSnapshotBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Push-only observer attaching Android widget updates to in-process Flow emissions after bootstrap.
 * Never polls devices or storage.
 */
object WidgetEventSync {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val configStore = WidgetConfigStore(appContext)

        scope.launch {
            FileApexServices.noteRepository.notes
                .map { notes -> BulletinWidgetSnapshotBuilder.build(notes) }
                .distinctUntilChanged()
                .collect {
                    AndroidWidgetBridge.notifyBulletinChanged(appContext)
                }
        }

        scope.launch {
            FileApexServices.deviceRepository.observeDevices()
                .map { devices -> devices.map { it.deviceId to it.deviceName }.sortedBy { it.first } }
                .distinctUntilChanged()
                .collect { pairs ->
                    configStore.retainPairedDevices(pairs.map { it.first }.toSet())
                    AndroidWidgetBridge.notifyAllChanged(appContext)
                }
        }

        scope.launch {
            FileApexServices.presenceMonitor.onlineDeviceIds
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    AndroidWidgetBridge.notifyAllChanged(appContext)
                }
        }
    }
}
