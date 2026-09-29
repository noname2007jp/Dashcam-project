/*
 * 参考: https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/InputSurface.java
 * および https://takusan.negitoro.dev/posts/android_add_canvas_text_to_video/ の実装を
 * 参考にKotlinで書き起こしたもの。
 *
 * Copyright (C) 2013 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0
 */
package com.example.dashcam.export

import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.view.Surface

/**
 * MediaCodecのエンコーダー入力Surfaceを、OpenGL経由で描画できるようにするクラス。
 *
 * MediaCodecのエンコーダー入力Surfaceには Canvas.lockCanvas() が使えないため、
 * デコード結果(動画フレーム)とCanvas(テキスト等)をOpenGLで一度合成してから
 * このSurfaceへ書き込む。
 */
class CodecInputSurface(
    private val surface: Surface,
    private val textureRenderer: TextureRenderer
) : SurfaceTexture.OnFrameAvailableListener {

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private val frameSyncObject = Object()
    private var frameAvailable = false
    private var surfaceTexture: SurfaceTexture? = null

    /** デコーダーの出力先として使うSurface(このSurfaceにデコード結果が描画される) */
    var drawSurface: Surface? = null
        private set

    init {
        eglSetup()
    }

    fun createRender() {
        textureRenderer.surfaceCreated()
        surfaceTexture = SurfaceTexture(textureRenderer.videoTextureID).also {
            it.setOnFrameAvailableListener(this)
        }
        drawSurface = Surface(surfaceTexture)
    }

    private fun eglSetup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        checkEglError("eglChooseConfig")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        checkEglError("eglCreateContext")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
    }

    fun awaitNewImage() {
        val timeoutMs = 5000L
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                frameSyncObject.wait(timeoutMs)
                if (!frameAvailable) {
                    throw RuntimeException("Surface frame wait timed out")
                }
            }
            frameAvailable = false
        }
        surfaceTexture?.updateTexImage()
    }

    /** デコードされたフレームとCanvasの内容を合成して描画する */
    fun drawImage(onCanvasDrawRequest: (Canvas) -> Unit) {
        val st = surfaceTexture ?: return
        textureRenderer.prepareDraw()
        textureRenderer.drawFrame(st)
        textureRenderer.drawCanvas(onCanvasDrawRequest)
        textureRenderer.invokeGlFinish()
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(frameSyncObject) {
            if (frameAvailable) {
                throw RuntimeException("frame available already set, frame could be dropped")
            }
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        surface.release()
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        checkEglError("eglMakeCurrent")
    }

    fun swapBuffers(): Boolean {
        val result = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        checkEglError("eglSwapBuffers")
        return result
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        checkEglError("eglPresentationTimeANDROID")
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$msg: EGL error: 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
