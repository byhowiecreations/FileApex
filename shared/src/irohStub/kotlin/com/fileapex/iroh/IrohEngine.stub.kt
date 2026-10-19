package com.fileapex.iroh

/** The Play bundle leaves out Iroh's prebuilt native library until its arm64 build is 16 KB aligned. */
actual fun embeddedIrohEngine(): IrohEngine? = null
