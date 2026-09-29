/*
 * 参考: https://takusan.negitoro.dev/posts/android_add_canvas_text_to_video/ の実装を
 * 参考にKotlinで書き起こしたもの。動画フレーム(External OESテクスチャ)とCanvas(2Dテクスチャ)を
 * フラグメントシェーダーで切り替えながら2回描画することで合成する。
 */
package com.example.dashcam.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGLで動画フレームとCanvasを合成して描画するクラス。
 *
 * @param outputVideoWidth エンコード後の動画の幅
 * @param outputVideoHeight エンコード後の動画の高さ
 * @param originVideoWidth 元動画の幅(回転考慮後)
 * @param originVideoHeight 元動画の高さ(回転考慮後)
 * @param videoRotationDegrees 元動画の回転情報を打ち消すための回転角度
 */
class TextureRenderer(
    private val outputVideoWidth: Int,
    private val outputVideoHeight: Int,
    private val originVideoWidth: Int,
    private val originVideoHeight: Int,
    private val videoRotationDegrees: Float
) {
    private val triangleVertices: FloatBuffer =
        ByteBuffer.allocateDirect(TRIANGLE_VERTICES_DATA.size * FLOAT_SIZE_BYTES).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(TRIANGLE_VERTICES_DATA)
                position(0)
            }
        }

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private val canvasBitmap: Bitmap by lazy {
        Bitmap.createBitmap(outputVideoWidth, outputVideoHeight, Bitmap.Config.ARGB_8888)
    }
    private val canvas: Canvas by lazy { Canvas(canvasBitmap) }

    private var program = 0
    private var uMVPMatrixHandle = 0
    private var uSTMatrixHandle = 0
    private var aPositionHandle = 0
    private var aTextureHandle = 0
    private var uCanvasTextureHandle = 0
    private var uVideoTextureHandle = 0
    private var uDrawVideoHandle = 0

    private var canvasTextureID = -1
    var videoTextureID = -1
        private set

    init {
        Matrix.setIdentityM(stMatrix, 0)
    }

    fun prepareDraw() {
        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")

        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle, 2, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
        )
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        // Snapdragon端末等で映像が乱れる対策
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun drawFrame(surfaceTexture: SurfaceTexture) {
        checkGlError("drawFrame start")
        surfaceTexture.getTransformMatrix(stMatrix)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureID)
        GLES20.glUniform1i(uVideoTextureHandle, 0)
        GLES20.glUniform1i(uCanvasTextureHandle, 1)

        GLES20.glUniform1i(uDrawVideoHandle, 1)
        Matrix.setIdentityM(mvpMatrix, 0)

        // アスペクト比を保ったまま出力サイズにフィットさせる
        val scaleY = outputVideoHeight / originVideoHeight.toFloat()
        val textureWidth = originVideoWidth * scaleY
        val percent = textureWidth / outputVideoWidth.toFloat()
        Matrix.scaleM(mvpMatrix, 0, percent, 1f, 1f)

        // 縦動画の回転情報を打ち消す
        Matrix.rotateM(mvpMatrix, 0, videoRotationDegrees, 0f, 0f, 1f)

        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays video")
    }

    fun drawCanvas(onCanvasDrawRequest: (Canvas) -> Unit) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTextureID)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        onCanvasDrawRequest(canvas)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, canvasBitmap)
        checkGlError("texSubImage2D canvas")

        GLES20.glUniform1i(uCanvasTextureHandle, 1)
        GLES20.glUniform1i(uDrawVideoHandle, 0)
        Matrix.setIdentityM(mvpMatrix, 0)

        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays canvas")
    }

    fun invokeGlFinish() {
        GLES20.glFinish()
    }

    fun surfaceCreated() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) throw RuntimeException("failed creating program")

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uCanvasTextureHandle = GLES20.glGetUniformLocation(program, "uCanvasTexture")
        uVideoTextureHandle = GLES20.glGetUniformLocation(program, "uVideoTexture")
        uDrawVideoHandle = GLES20.glGetUniformLocation(program, "uDrawVideo")

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        videoTextureID = textures[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureID)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        canvasTextureID = textures[1]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTextureID)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, canvasBitmap, 0)

        // Canvas側の透明部分を正しく透明合成するためアルファブレンドを有効化
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) return 0

        var newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) return 0
        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(newProgram)
            newProgram = 0
        }
        return newProgram
    }

    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }

    companion object {
        private val TRIANGLE_VERTICES_DATA = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uVideoTexture;
            uniform sampler2D uCanvasTexture;
            uniform int uDrawVideo;
            void main() {
                vec4 videoTexture = texture2D(uVideoTexture, vTextureCoord);
                vec4 canvasTexture = texture2D(uCanvasTexture, vTextureCoord);
                if (bool(uDrawVideo)) {
                    gl_FragColor = videoTexture;
                } else {
                    gl_FragColor = canvasTexture;
                }
            }
        """
    }
}
