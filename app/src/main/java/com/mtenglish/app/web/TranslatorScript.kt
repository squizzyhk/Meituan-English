package com.mtenglish.app.web

import android.content.Context

/**
 * Loads `assets/inject/translator.js` and prefixes it with the per-page configuration the script reads
 * from `window.__mteConfig` at install time.
 */
internal object TranslatorScript {
    private const val ASSET = "inject/translator.js"

    @Volatile
    private var source: String? = null

    fun source(context: Context): String =
        source ?: context.applicationContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .also { source = it }

    /** Full script to inject: config line + translator. */
    fun build(context: Context, enabled: Boolean, images: Boolean, longPress: Boolean): String =
        "window.__mteConfig={enabled:$enabled,images:$images,longPress:$longPress};\n" + source(context)
}
