package com.gemini.ocr.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.CostOptimizationEngine
import com.gemini.ocr.network.SelfLearningDatabase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.text.Normalizer

data class OcrBlock(val id: String, val text: String, val rect: Rect) {
    fun json(width: Int, height: Int): JSONObject = JSONObject().put("id", id).put("text", text.take(2000))
        .put("box", JSONObject().put("x", rect.left.toDouble() / width).put("y", rect.top.toDouble() / height)
            .put("width", rect.width().toDouble() / width).put("height", rect.height().toDouble() / height))
}

class OcrProcessor(private val language: String) : AutoCloseable {
    private val recognizer: TextRecognizer = createRecognizer(language)

    suspend fun process(
        bitmap: Bitmap,
        retainSourceFragments: Boolean = false,
        fastDownscale: Boolean = true,
        ocrPreCorrection: Boolean = true
    ): List<OcrBlock> {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val scale = if (fastDownscale && maxDim > MAX_OCR_DIMENSION) {
            MAX_OCR_DIMENSION.toFloat() / maxDim.toFloat()
        } else {
            1f
        }
        val targetBitmap = if (scale < 0.95f) {
            val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(16)
            val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(16)
            Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        } else {
            bitmap
        }
        val result = try {
            recognizer.process(InputImage.fromBitmap(targetBitmap, 0)).await()
        } finally {
            if (targetBitmap !== bitmap && !targetBitmap.isRecycled) {
                targetBitmap.recycle()
            }
        }
        val invScale = if (scale < 0.95f) 1f / scale else 1f
        var total = 0
        return result.textBlocks.flatMap { it.lines }.mapNotNull { block ->
            val rawRect = block.boundingBox ?: return@mapNotNull null
            val rect = if (invScale != 1f) {
                Rect(
                    (rawRect.left * invScale).toInt(),
                    (rawRect.top * invScale).toInt(),
                    (rawRect.right * invScale).toInt(),
                    (rawRect.bottom * invScale).toInt()
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
            // ML Kit sometimes does not supply confidence, so reject only known low-confidence lines.
            val confidence = block.confidence?.toDouble() ?: Double.NaN
            val singleCjk = text.length == 1 && Character.UnicodeScript.of(text[0].code) in setOf(
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
            val fragment = retainSourceFragments && text.any { it.isDigit() || it == '_' } &&
                text.all { it.isDigit() || it == '_' || it == '.' || it == '-' || it.isWhitespace() }
            if (CostOptimizationEngine.isOcrNoise(text, language, retainSourceFragments) && !singleCjk) {
                SessionState.noiseFiltered.value++
                return@mapNotNull null
            }
            if ((text.length < 2 && !singleCjk && !fragment) || (text.none { it.isLetter() } && !fragment) || text.length > 2000 || (!confidence.isNaN() && confidence < .35)) return@mapNotNull null
            val clipped = Rect(rect)
            if (!clipped.intersect(0, 0, bitmap.width, bitmap.height) || clipped.width() < 3 || clipped.height() < 3) return@mapNotNull null
            total += text.length
            if (total > 12000) return@mapNotNull null
            OcrBlock("b${block.hashCode().toUInt().toString(16)}", text, clipped)
        }.sortedWith(compareBy({ it.rect.top / 24 }, { it.rect.left })).take(40)
            .mapIndexed { i, block -> block.copy(id = "b$i") }
    }

    override fun close() { recognizer.close() }

    companion object {
        private const val MAX_OCR_DIMENSION = 960

        private fun createRecognizer(language: String): TextRecognizer = when (language) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        suspend fun preWarm(language: String) {
            val client = createRecognizer(language)
            val dummy = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                client.process(InputImage.fromBitmap(dummy, 0)).await()
            } catch (_: Exception) {
            } finally {
                dummy.recycle()
                client.close()
            }
        }
    }
}
