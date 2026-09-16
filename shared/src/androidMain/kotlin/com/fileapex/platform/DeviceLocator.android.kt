package com.fileapex.platform

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

actual fun triggerLocalLocatorSound() {
    CoroutineScope(Dispatchers.Default).launch {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            repeat(4) {
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
                delay(400)
            }
            toneGen.release()
        }
    }
}
