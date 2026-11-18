package com.fileapex.platform

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import com.fileapex.domain.diagnostics.GpuDiagnostics
import com.fileapex.domain.diagnostics.GpuNames
import com.fileapex.util.cancellableCatching

/** Android only exposes the GPU name through a GL context, so one 1x1 offscreen context reads the renderer string once. */
internal object AndroidGraphics {
    @Volatile
    private var renderer: String? = null

    fun read(): List<GpuDiagnostics> {
        val name = GpuNames.fromGlRenderer(renderer ?: queryRenderer()?.also { renderer = it }) ?: return emptyList()
        return listOf(GpuDiagnostics(name, null))
    }

    // The default display is shared with the app's own rendering, so it is initialized but never terminated here.
    private fun queryRenderer(): String? = cancellableCatching("AndroidGraphics queryRenderer") {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return@cancellableCatching null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return@cancellableCatching null
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1) return@cancellableCatching null
        val context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) return@cancellableCatching null
        val surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return@cancellableCatching null
            GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }.onFailure { println("AndroidGraphics: GL renderer unavailable (${it::class.simpleName})") }.getOrNull()
}
