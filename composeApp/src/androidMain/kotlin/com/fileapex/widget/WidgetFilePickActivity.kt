package com.fileapex.widget

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.fileapex.MainActivity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.domain.widget.WidgetTargetBindingCodec
import com.fileapex.drop.GuestDropHub
import com.fileapex.drop.GuestDropLimits
import com.fileapex.drop.ShareResult
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.AndroidShareIntake
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.platform.PlatformClipboard
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Translucent system file picker launcher for Drop Target, Send File, and Share as Link widgets.
 */
class WidgetFilePickActivity : ComponentActivity() {

    private var pickMode: String = WidgetDeepLinks.PICK_MODE_SEND
    private var binding: WidgetTargetBinding = WidgetTargetBinding.AskEachTime

    private val sendPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedSendUris(uris)
    }

    private val singleLinkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handlePickedGuestLinkUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) {
            BriefToast.show("Unlock device storage first")
            finish()
            return
        }
        pickMode = intent?.getStringExtra(WidgetDeepLinks.EXTRA_PICK_MODE) ?: WidgetDeepLinks.PICK_MODE_SEND
        binding = WidgetTargetBindingCodec.decode(intent?.getStringExtra(WidgetDeepLinks.EXTRA_BINDING_WIRE))

        if (savedInstanceState == null) {
            if (pickMode == WidgetDeepLinks.PICK_MODE_GUEST_LINK) {
                if (!GuestDropHub.available || !GuestDropHub.consent().allowed) {
                    BriefToast.show(AppI18n.t("widget_link_unavailable"))
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                    return
                }
                singleLinkPicker.launch(arrayOf("*/*"))
            } else {
                sendPicker.launch(arrayOf("*/*"))
            }
        }
    }

    private fun handlePickedSendUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finish()
            return
        }
        val targetIds = when (val b = binding) {
            is WidgetTargetBinding.SpecificDevice -> listOf(b.deviceId)
            WidgetTargetBinding.AllOnline -> FileApexServices.presenceMonitor.onlineDeviceIds.value.toList()
            WidgetTargetBinding.AskEachTime -> {
                launchShareSendInMainActivity(uris)
                finish()
                return
            }
        }
        if (targetIds.isEmpty()) {
            BriefToast.show(AppI18n.t("widget_no_online_devices"))
            finish()
            return
        }
        lifecycleScope.launch {
            cancellableCatching {
                val payload = withContext(Dispatchers.IO) {
                    AndroidShareIntake.stageShareUris(this@WidgetFilePickActivity, uris, UUID.randomUUID().toString())
                }
                val paths = payload.files.map { it.absolutePath }.filter { it.isNotBlank() }
                if (paths.isEmpty()) {
                    BriefToast.show(AppI18n.t("no_shared_file"))
                } else {
                    val result = withContext(Dispatchers.IO) {
                        FileApexServices.transferQueue.sendLocalPathsOrQueue(paths, targetIds)
                    }
                    val batch = result.batch
                    if ((batch != null && !batch.allFailed) || result.hadQueue || result.hadRelay) {
                        targetIds.forEach { com.fileapex.platform.recordDirectShareTargetUsed(it) }
                    }
                    BriefToast.show(result.message)
                }
            }.onFailure { error ->
                BriefToast.show(error.message ?: AppI18n.t("widget_action_failed"))
            }
            finish()
        }
    }

    private fun handlePickedGuestLinkUri(uri: Uri?) {
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            cancellableCatching {
                val payload = withContext(Dispatchers.IO) {
                    AndroidShareIntake.stageShareUris(this@WidgetFilePickActivity, listOf(uri), UUID.randomUUID().toString())
                }
                val staged = payload.files.firstOrNull()?.takeIf { it.absolutePath.isNotBlank() }
                if (staged == null) {
                    BriefToast.show(AppI18n.t("no_shared_file"))
                    finish()
                    return@launch
                }
                when (val shared = withContext(Dispatchers.IO) { GuestDropHub.service.share(File(staged.absolutePath), GuestDropLimits.default.ttlMs) }) {
                    is ShareResult.Ready -> {
                        PlatformClipboard.setSystemClipboardText(shared.url)
                        BriefToast.show(AppI18n.t("widget_link_copied", staged.fileName))
                    }
                    is ShareResult.Failed -> BriefToast.show(AppI18n.t("widget_action_failed"))
                }
            }.onFailure { error ->
                BriefToast.show(error.message ?: AppI18n.t("widget_action_failed"))
            }
            finish()
        }
    }

    private fun launchShareSendInMainActivity(uris: List<Uri>) {
        val shareIntent = Intent(this, MainActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            val clip = ClipData.newRawUri("shared", uris.first())
            for (i in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[i]))
            }
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(shareIntent)
    }
}
