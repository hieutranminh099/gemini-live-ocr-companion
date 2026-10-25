package com.gemini.ocr.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.CostOptimizationEngine
import com.gemini.ocr.network.SelfLearningDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.text.Normalizer

data class OcrBlock(val id: String, val text: String, val rect: Rect) {
    fun json(width: Int, height: Int): JSONObject = JSONObject().put("id", id).put("text", text.take(2000))
        .put("box", JSONObject().put("x", rect.left.toDouble() / width).put("y", rect.top.toDouble() / height)
            .put("width", rect.width().toDouble() / width).put("height", rect.height().toDouble() / height))
}

/**
 * Dedicated Chinese (Simplified & Traditional) ML Kit OCR Processor.
 *
 * Implements ROI height downscaling to 160px-240px before running ML Kit Chinese Text Recognition
 * so OCR latency stays within 40ms-80ms on both Android devices and BlueStacks emulators.
 */
class OcrProcessor(@Suppress("UNUSED_PARAMETER") private val language: String = "zh") : AutoCloseable {
    private var recognizerInstance: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        return recognizerInstance ?: createRecognizer().also { recognizerInstance = it }
    }

    suspend fun process(
        bitmap: Bitmap,
        retainSourceFragments: Boolean = false,
        fastDownscale: Boolean = true,
        ocrPreCorrection: Boolean = true
    ): List<OcrBlock> {
        val scale = computeDownscaleFactor(bitmap.width, bitmap.height, fastDownscale)
        val targetBitmap = if (scale < 0.96f) {
            val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(16)
            val scaledH = (bitmap.height * scale).toInt().coerceIn(MIN_ROI_HEIGHT_PX, MAX_ROI_HEIGHT_PX)
            Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        } else {
            bitmap
        }
        val scaleX = bitmap.width.toFloat() / targetBitmap.width.toFloat()
        val scaleY = bitmap.height.toFloat() / targetBitmap.height.toFloat()

        val result = try {
            getRecognizer().process(InputImage.fromBitmap(targetBitmap, 0)).await()
        } finally {
            if (targetBitmap !== bitmap && !targetBitmap.isRecycled) {
                targetBitmap.recycle()
            }
        }

        var total = 0
        return result.textBlocks.flatMap { it.lines }.mapNotNull { block ->
            val rawRect = block.boundingBox ?: return@mapNotNull null
            val rect = if (targetBitmap !== bitmap) {
                Rect(
                    (rawRect.left * scaleX).toInt(),
                    (rawRect.top * scaleY).toInt(),
                    (rawRect.right * scaleX).toInt(),
                    (rawRect.bottom * scaleY).toInt()
                )
            } else {
                rawRect
            }
            val rawNormalized = Normalizer.normalize(block.text, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
            val text = if (ocrPreCorrection) {
                SelfLearningDatabase.preprocessAndCorrectOcr(rawNormalized)
            } else {
                rawNormalized
            }
            val confidence = block.confidence?.toDouble() ?: Double.NaN
            val singleHan = text.length == 1 && Character.UnicodeScript.of(text[0].code) == Character.UnicodeScript.HAN
            val fragment = retainSourceFragments && text.any { it.isDigit() || it == '_' } &&
                text.all { it.isDigit() || it == '_' || it == '.' || it == '-' || it.isWhitespace() }
            if (CostOptimizationEngine.isOcrNoise(text, "zh", retainSourceFragments) && !singleHan) {
                SessionState.noiseFiltered.value++
                return@mapNotNull null
            }
            if ((text.length < 2 && !singleHan && !fragment) || (text.none { it.isLetter() } && !fragment) || text.length > 2000 || (!confidence.isNaN() && confidence < .35)) return@mapNotNull null
            val clipped = Rect(rect)
            if (!clipped.intersect(0, 0, bitmap.width, bitmap.height) || clipped.width() < 3 || clipped.height() < 3) return@mapNotNull null
            total += text.length
            if (total > 12000) return@mapNotNull null
            OcrBlock("b${block.hashCode().toUInt().toString(16)}", text, clipped)
        }.sortedWith(compareBy({ it.rect.top / 24 }, { it.rect.left })).take(40)
            .mapIndexed { i, block -> block.copy(id = "b$i") }
    }

    override fun close() {
        try {
            recognizerInstance?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val MIN_ROI_HEIGHT_PX = 160
        const val TARGET_ROI_HEIGHT_PX = 200
        const val MAX_ROI_HEIGHT_PX = 240
        private const val MAX_OCR_WIDTH_PX = 960

        fun computeDownscaleFactor(width: Int, height: Int, fastDownscale: Boolean = true): Float {
            if (!fastDownscale || width <= 0 || height <= 0) return 1f
            val heightScale = if (height > MAX_ROI_HEIGHT_PX) {
                TARGET_ROI_HEIGHT_PX.toFloat() / height.toFloat()
            } else {
                1f
            }
            val widthScale = if (width * heightScale > MAX_OCR_WIDTH_PX) {
                MAX_OCR_WIDTH_PX.toFloat() / width.toFloat()
            } else {
                heightScale
            }
            return minOf(heightScale, widthScale).coerceIn(0.1f, 1f)
        }

        private fun createRecognizer(): TextRecognizer =
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        suspend fun preWarm(@Suppress("UNUSED_PARAMETER") language: String = "zh") {
            try {
                val client = createRecognizer()
                val dummy = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    client.process(InputImage.fromBitmap(dummy, 0)).await()
                } catch (_: Throwable) {
                } finally {
                    dummy.recycle()
                    client.close()
                }
            } catch (_: Throwable) {
            }
        }
    }
}
