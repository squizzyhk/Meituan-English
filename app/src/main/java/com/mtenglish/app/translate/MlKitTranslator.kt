package com.mtenglish.app.translate

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.mtenglish.app.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline fallback using ML Kit on-device zh->en translation. Only used when the user enabled the
 * offline fallback AND the Chinese model has been downloaded ([downloadModel]).
 */
class MlKitTranslator(private val prefs: Prefs) : Translator {
    override val id: String = ID

    private val translator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build(),
        )
    }

    @Volatile private var modelReady = false

    override suspend fun isAvailable(): Boolean = prefs.offlineFallback && isModelDownloaded()

    suspend fun isModelDownloaded(): Boolean {
        if (modelReady) return true
        return try {
            val model = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
            RemoteModelManager.getInstance().isModelDownloaded(model).await().also { modelReady = it }
        } catch (e: Exception) {
            Log.w(TAG, "Model check failed", e)
            false
        }
    }

    /** Downloads the zh model if needed (any network). Returns true when it is ready. */
    suspend fun downloadModel(): Boolean = try {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        modelReady = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "Model download failed", e)
        false
    }

    override suspend fun translate(batch: List<TranslationRequest>): Map<String, String> {
        val out = HashMap<String, String>()
        for (req in batch) {
            if (req.source in out || req.source.isBlank()) continue
            try {
                val en = translator.translate(req.source).await().trim()
                if (en.isNotEmpty() && !ChineseText.containsChinese(en)) out[req.source] = en
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit translation failed", e)
                if (!modelReady) break
            }
        }
        return out
    }

    companion object {
        const val ID = "mlkit"
        private const val TAG = "MtMlKit"
        private val DIRECT = Executor { it.run() }

        /** Bridges a Play Services [Task] to a suspending call without extra dependencies. */
        internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener(DIRECT) { cont.resume(it) }
            addOnFailureListener(DIRECT) { cont.resumeWithException(it) }
            addOnCanceledListener(DIRECT) { cont.cancel() }
        }
    }
}
