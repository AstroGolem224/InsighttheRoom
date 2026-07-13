package itr.scan

import android.content.Context
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import itr.core.ar.CameraImage
import itr.core.scan.BoundingBox
import java.nio.ByteBuffer

data class Detection(val label: String, val normalizedBox: BoundingBox, val confidence: Double)

class Detector internal constructor(
    private val objectDetector: ObjectDetector,
    private val scoreThreshold: Double = SCORE_THRESHOLD,
) : AutoCloseable {
    fun detect(image: CameraImage): List<Detection> {
        val mpImage = ByteBufferImageBuilder(
            ByteBuffer.wrap(image.copyBytes()),
            image.width,
            image.height,
            MPImage.IMAGE_FORMAT_RGBA,
        ).build()
        return try {
            objectDetector.detect(mpImage).detections().mapNotNull { raw ->
                runCatching {
                    val category = raw.categories().maxByOrNull { it.score() } ?: return@runCatching null
                    val label = category.categoryName()
                    val confidence = category.score().toDouble()
                    if (label !in COCO_ALLOW_LIST || !confidence.isFinite() || confidence < scoreThreshold) {
                        return@runCatching null
                    }

                    val pixelBox = raw.boundingBox()
                    val rawEdges = listOf(
                        pixelBox.left.toDouble() / image.width,
                        pixelBox.top.toDouble() / image.height,
                        pixelBox.right.toDouble() / image.width,
                        pixelBox.bottom.toDouble() / image.height,
                    )
                    if (rawEdges.any { !it.isFinite() }) return@runCatching null
                    val left = rawEdges[0].coerceIn(0.0, 1.0)
                    val top = rawEdges[1].coerceIn(0.0, 1.0)
                    val right = rawEdges[2].coerceIn(0.0, 1.0)
                    val bottom = rawEdges[3].coerceIn(0.0, 1.0)
                    if (right <= left || bottom <= top) return@runCatching null
                    Detection(label, BoundingBox(left, top, right, bottom), confidence)
                }.getOrNull()
            }
        } finally {
            mpImage.close()
        }
    }

    override fun close() = objectDetector.close()

    companion object {
        const val SCORE_THRESHOLD = 0.4
        val COCO_ALLOW_LIST = listOf(
            "chair", "couch", "bed", "dining table", "tv", "potted plant", "refrigerator",
            "bench", "toilet", "sink", "oven", "microwave", "book", "clock", "vase",
        )
    }
}

object DetectorFactory {
    private const val MODEL_ASSET = "efficientdet_lite0.tflite"
    private const val MAX_RESULTS = 20

    fun create(context: Context): Detector {
        fun createWith(delegate: Delegate): ObjectDetector {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setScoreThreshold(Detector.SCORE_THRESHOLD.toFloat())
                .setMaxResults(MAX_RESULTS)
                .setCategoryAllowlist(Detector.COCO_ALLOW_LIST)
                .build()
            return ObjectDetector.createFromOptions(context, options)
        }

        val objectDetector = runCatching { createWith(Delegate.GPU) }
            .getOrElse { createWith(Delegate.CPU) }
        return Detector(objectDetector)
    }
}
