package com.mtenglish.app.translate

import android.content.Context

/** PLACEHOLDER - full implementation provided by the translate module. */
class TranslationEngine private constructor(private val context: Context) {
    suspend fun translate(batch: List<TranslationRequest>): Map<String, String> = emptyMap()

    companion object {
        fun create(context: Context) = TranslationEngine(context.applicationContext)
    }
}
