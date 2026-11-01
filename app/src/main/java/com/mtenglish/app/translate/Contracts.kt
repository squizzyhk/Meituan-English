package com.mtenglish.app.translate

/**
 * Shared contracts for the translation pipeline. Every translation backend implements
 * [Translator]; [TranslationEngine] chains them:
 *   Glossary (exact UI/food terms) -> TranslationCache (SQLite) -> ClaudeTranslator (natural English,
 *   needs API key) -> MlKitTranslator (offline fallback) -> original text.
 */

/** What kind of text this is. Lets the LLM pick tone/length (a button label vs. a dish description). */
enum class TextKind { UI_LABEL, MENU_ITEM, STORE_NAME, DESCRIPTION, REVIEW, ADDRESS, FORM_HINT, IMAGE_TEXT, GENERIC }

data class TranslationRequest(
    val source: String,
    val kind: TextKind = TextKind.GENERIC,
    /** Optional surrounding context such as page title or store name, used to disambiguate. */
    val context: String? = null,
)

interface Translator {
    /** Short id stored with cached results, e.g. "glossary", "claude", "mlkit". */
    val id: String

    /** True when the backend can currently run (has API key, model downloaded, etc). */
    suspend fun isAvailable(): Boolean

    /**
     * Translate a batch. Returns a map from source string to English for every string it could
     * translate; strings it could not handle are simply absent from the map.
     */
    suspend fun translate(batch: List<TranslationRequest>): Map<String, String>
}
