package com.fileapex.data.bulletin

expect object BulletinMediaHelper {
    fun buildImagePreviewBase64(absolutePath: String): String?
    fun isImageFile(fileName: String): Boolean
}
