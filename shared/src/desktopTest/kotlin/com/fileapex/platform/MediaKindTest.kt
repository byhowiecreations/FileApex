package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaKindTest {
    @Test
    fun imagesVideosAndAudioGetTheirOwnCollection() {
        assertEquals(MediaKind.IMAGE, mediaKindOf("image/jpeg"))
        assertEquals(MediaKind.VIDEO, mediaKindOf("video/mp4"))
        assertEquals(MediaKind.AUDIO, mediaKindOf("audio/ogg"))
    }

    @Test
    fun theTypeIsReadCaseInsensitively() {
        assertEquals(MediaKind.IMAGE, mediaKindOf("IMAGE/PNG"))
    }

    @Test
    fun otherFilesAndEmptyTypesHaveNoMediaCollection() {
        assertNull(mediaKindOf("application/pdf"))
        assertNull(mediaKindOf(""))
        assertNull(mediaKindOf(null))
    }

    @Test
    fun theTypeComesFromTheFileNameWhenTheStoreHasNone() {
        assertEquals(MediaKind.IMAGE, mediaKindOfName("starryboo.jpg"))
        assertNull(mediaKindOfName("book.pdf"))
    }
}
