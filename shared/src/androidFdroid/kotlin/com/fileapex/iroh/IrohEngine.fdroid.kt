package com.fileapex.iroh

/** F-Droid builds leave out Iroh's prebuilt native library. */
actual fun embeddedIrohEngine(): IrohEngine? = null
