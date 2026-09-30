package com.example.dashcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * 背面カメラのレンズ切り替えを扱うヘルパー。
 *
 * Pixelを含む多くの端末では、広角・超広角は別々のカメラIDとして公開されておらず、
 * 1つの論理カメラ(logical camera)がズーム倍率に応じて内部的にレンズを切り替える
 * 構成になっている。そのため「別カメラIDを選ぶ」方式ではなく、
 * ズーム倍率(1.0未満で超広角に切り替わる)を指定する方式を使う。
 *
 * 最小ズーム倍率は CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE (Android 11以降)
 * から取得する。これが1.0未満であれば超広角レンズにアクセス可能と判断する。
 */
object CameraLensHelper {

    data class ZoomLensOption(
        val label: String,
        val zoomRatio: Float
    )

    private const val ULTRA_WIDE_THRESHOLD = 0.9f

    /**
     * 背面カメラで選択可能なレンズ(ズーム倍率)の一覧を返す。
     * 超広角に対応していない端末では「標準」のみが1件返る。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun listZoomLensOptions(cameraProvider: ProcessCameraProvider): List<ZoomLensOption> {
        val backInfo = cameraProvider.availableCameraInfos.firstOrNull { info ->
            CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(info)).isNotEmpty()
        } ?: return listOf(ZoomLensOption("標準", 1.0f))

        val minZoomRatio = getMinZoomRatio(backInfo)

        val options = mutableListOf(ZoomLensOption("標準", 1.0f))
        if (minZoomRatio != null && minZoomRatio < ULTRA_WIDE_THRESHOLD) {
            options.add(0, ZoomLensOption("超広角", minZoomRatio))
        }
        return options
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun getMinZoomRatio(cameraInfo: androidx.camera.core.CameraInfo): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null // API 30未満は非対応
        return try {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val range = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
            )
            range?.lower
        } catch (e: Exception) {
            null
        }
    }
}
