package com.mtenglish.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.mtenglish.app.translate.TranslationEngine
import java.io.File

/** One OCR'd line/block of Chinese text in an image, with its English translation. */
data class TranslatedRegion(val box: Rect, val original: String, val english: String)

/** PLACEHOLDER - full implementation provided by the image module. */
class ImageTextTranslator(private val context: Context, private val engine: TranslationEngine) {
    suspend fun translateImageUrl(url: String, headers: Map<String, String> = emptyMap()): File? = null
    suspend fun translateRegions(bitmap: Bitmap): List<TranslatedRegion> = emptyList()
    suspend fun translateBitmap(bitmap: Bitmap): Bitmap? = null
}
