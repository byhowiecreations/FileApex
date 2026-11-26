package com.fileapex.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun encodePng(bitmap: ImageBitmap): ByteArray? {
    val output = ByteArrayOutputStream()
    return if (bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
}
