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
            BriefToast.show("FileApex is still starting…")
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
                    Text("Sending clipboard…")
                }
            }
        }
        window.decorView.postDelayed({
            if (started.get()) return@postDelayed
            if (hasWindowFocus()) {
                pushAndFinish()
            } else {
                BriefToast.show("Open FileApex and tap Send Clipboard")
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
            var text = PlatformClipboard.readClipboardText(this@ClipboardPushActivity)
            var attempt = 0
            while (text.isNullOrBlank() && attempt < 6) {
                delay(150)
                text = PlatformClipboard.readClipboardText(this@ClipboardPushActivity)
                attempt++
            }
            BriefToast.show(ClipboardShareCoordinator.pushCurrentClipboardNow(text))
            finish()
        }
    }
}
