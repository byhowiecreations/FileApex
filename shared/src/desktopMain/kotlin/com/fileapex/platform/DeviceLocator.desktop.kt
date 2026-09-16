package com.fileapex.platform

import java.awt.Toolkit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

actual fun triggerLocalLocatorSound() {
    CoroutineScope(Dispatchers.Default).launch {
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            repeat(4) {
                toolkit.beep()
                delay(350)
            }
        }
    }
}
