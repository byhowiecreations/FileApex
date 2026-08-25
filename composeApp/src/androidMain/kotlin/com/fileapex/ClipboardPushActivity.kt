package com.fileapex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.platform.PlatformClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardPushActivity : ComponentActivity() {
    private val started = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) {
            BriefToast.show(com.fileapex.i18n.AppI18n.t("fileapex_still_starting"))
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(com.fileapex.i18n.AppI18n.t("sending_clipboard"))
                }
            }
        }
        window.decorView.postDelayed({
            if (started.get()) return@postDelayed
            if (hasWindowFocus() || com.fileapex.platform.ClipboardShizukuAccess.isReady()) {
                pushAndFinish()
            } else {
                BriefToast.show(com.fileapex.i18n.AppI18n.t("open_fileapex_send_clipboard"))
                finish()
            }
        }, 2_500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.postDelayed({
                pushAndFinish()
            }, 350)
        }
    }

    private fun pushAndFinish() {
        if (!started.compareAndSet(false, true)) return
        lifecycleScope.launch {
            var text = readClipText()
            var attempt = 0
            while (text.isNullOrBlank() && attempt < 6) {
                delay(150)
                text = readClipText()
                attempt++
            }
            BriefToast.show(ClipboardShareCoordinator.pushCurrentClipboardNow(text))
            finish()
        }
    }

    private fun readClipText(): String? {
        PlatformClipboard.readClipboardText(this)?.takeIf { it.isNotBlank() }?.let { return it }
        return com.fileapex.platform.ClipboardShizukuAccess.tryReadText()
    }
}
