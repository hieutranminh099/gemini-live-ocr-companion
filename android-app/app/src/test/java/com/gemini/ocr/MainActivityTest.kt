package com.gemini.ocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val reset = object : ExternalResource() {
        override fun before() {
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            SessionState.running.value = false; SessionState.connected.value = false; SessionState.paused.value = false
            SessionState.status.value = "Sẵn sàng kết nối"
            SessionState.backend.value = TranslationBackendState()
            SessionState.lastOcr.value = ""; SessionState.lastTranslation.value = ""
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)

    @Test fun standaloneHomeDisplaysTwoSimplifiedModesAndStartsWithoutPc() {
        compose.onNodeWithText("Hiểu mọi\nkhung hình.").assertIsDisplayed()
        screenshot("android-home.png")
        compose.onNodeWithText("Bật dịch màn hình").assertIsDisplayed()
        compose.onNodeWithTag("mode_bank_only_card").performScrollTo().assertExists()
        compose.onNodeWithTag("mode_hybrid_gemini_card").performScrollTo().assertExists()
    }
    @Test fun translationBankTabDisplaysSelfLearningRecordsAndOcrPreCorrection() {
        compose.onNodeWithText("Kho thoại", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Kho thoại tự học").assertIsDisplayed()
        compose.onNodeWithTag("dialogue_bank_test_result").assertExists()
    }
    @Test fun switchingBetweenMode1AndMode2UpdatesAppSettings() {
        val settings = com.gemini.ocr.settings.AppSettings(compose.activity)
        compose.onNodeWithTag("mode_bank_only_card").performScrollTo().performClick()
        org.junit.Assert.assertEquals("bank_only", settings.translationMode)
        compose.onNodeWithTag("mode_hybrid_gemini_card").performScrollTo().performClick()
        org.junit.Assert.assertEquals("hybrid_gemini", settings.translationMode)
    }
    @Test fun customizationScreenIsReachableWithoutConnection() {
        compose.onNodeWithText("Tùy chỉnh", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Theo cách của bạn").assertIsDisplayed()
        compose.onNodeWithText("Vùng nhận diện").performScrollTo().assertIsDisplayed()
        screenshot("android-settings.png")
    }
    @Test fun oneFramePresetCanBeSelectedBeforeStartingCapture() {
        compose.onNodeWithText("Dùng kiểu phụ đề 1 khung").performScrollTo().performClick()
        compose.onNodeWithText("Đã chọn · Phụ đề 1 khung").assertExists()
        compose.onNodeWithText("Chạm dịch 1 khung").performScrollTo().assertExists()
        val settings = com.gemini.ocr.settings.AppSettings(compose.activity)
        org.junit.Assert.assertTrue(settings.subtitleFocus)
        org.junit.Assert.assertEquals("cover", settings.overlay)
        org.junit.Assert.assertFalse(SessionState.running.value)
    }
    @Test fun recognizedChineseAndItsTranslationRemainReadableInSessionDetails() {
        compose.runOnIdle {
            SessionState.running.value = true
            SessionState.lastOcr.value = "亏你还是韩国人"
            SessionState.lastTranslation.value = "Thế mà cũng là người Hàn Quốc"
        }
        compose.onNodeWithText("Khung vừa đọc").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OCR: 亏你还是韩国人").assertExists()
        compose.onNodeWithText("Thế mà cũng là người Hàn Quốc").assertExists()
    }
    @Test fun automaticPresetKeepsLanguagePairingAndCustomRegion() {
        val settings = com.gemini.ocr.settings.AppSettings(compose.activity)
        val region = android.graphics.RectF(.2f, .8f, .9f, .95f)
        settings.language = "zh"; settings.host = "192.168.1.9:8765"; settings.subtitleRegion = region
        compose.onNodeWithText("Tự động tăng tốc · Gemini").performScrollTo().performClick()
        compose.onNodeWithText("Đã chọn · Tự động tăng tốc").assertExists()
        org.junit.Assert.assertEquals("auto", settings.subtitleCapture)
        org.junit.Assert.assertEquals(150L, settings.interval)
        org.junit.Assert.assertEquals("fast", settings.mode)
        org.junit.Assert.assertEquals("zh", settings.language)
        org.junit.Assert.assertEquals("192.168.1.9:8765", settings.host)
        org.junit.Assert.assertEquals(region, settings.subtitleRegion)
        org.junit.Assert.assertFalse(SessionState.running.value)
    }
    @Test fun connectedBadgeChangesFromCacheOnlyToExperimentalWithoutRestart() {
        compose.runOnIdle {
            SessionState.connected.value = true
            SessionState.backend.value = TranslationBackendState("cli", "cache_only", false)
        }
        compose.onNodeWithText("Chỉ dùng cache / từ điển • Chưa bật dịch câu mới").assertExists()
        compose.runOnIdle {
            SessionState.backend.value = TranslationBackendState("cli", "cli_experimental", true, true)
        }
        compose.onNodeWithText("CLI cá nhân thử nghiệm • Đã bật dịch câu mới").assertExists()
        compose.onNodeWithText("Chỉ dùng cache / từ điển • Chưa bật dịch câu mới").assertDoesNotExist()
    }
    private fun screenshot(name: String) {
        val directory = File(requireNotNull(System.getProperty("gemini.test.screenshots")))
        directory.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
