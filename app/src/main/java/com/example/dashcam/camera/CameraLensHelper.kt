package com.example.dashcam.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * 端末の背面カメラを列挙し、広角(超広角)対応の場合に選択できるようにするヘルパー。
 *
 * 焦点距離(LENS_INFO_AVAILABLE_FOCAL_LENGTHS)の最小値が小さいカメラほど
 * 画角が広い(広角寄り)とみなして並べ替える。焦点距離だけで正確な画角種別を
 * 判定することはできないため、あくまで簡易的な目安として扱う。
 */
object CameraLensHelper {

    data class BackCameraOption(
        val cameraId: String,
        val minFocalLengthMm: Float?
    )

    @OptIn(ExperimentalCamera2Interop::class)
    fun listBackCameraOptions(cameraProvider: ProcessCameraProvider): List<BackCameraOption> {
        val backCameraInfos: List<CameraInfo> = cameraProvider.availableCameraInfos.filter {
            CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty()
        }

        return backCameraInfos.map { info ->
            val camera2Info = Camera2CameraInfo.from(info)
            val focalLengths = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )
            BackCameraOption(
                cameraId = camera2Info.cameraId,
                minFocalLengthMm = focalLengths?.minOrNull()
            )
        }.sortedBy { it.minFocalLengthMm ?: Float.MAX_VALUE }
    }

    /** 表示用ラベル("広角"/"標準"/"望遠"等)を付けたリストを返す */
    fun listBackCameraOptionsWithLabels(
        cameraProvider: ProcessCameraProvider
    ): List<Pair<BackCameraOption, String>> {
        val options = listBackCameraOptions(cameraProvider)
        return options.mapIndexed { index, option ->
            val label = when {
                options.size == 1 -> "標準"
                index == 0 -> "広角"
                index == options.size - 1 && options.size >= 3 -> "望遠"
                else -> "標準"
            }
            option to label
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun selectorForCameraId(cameraId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
            }
            .build()
    }
}
