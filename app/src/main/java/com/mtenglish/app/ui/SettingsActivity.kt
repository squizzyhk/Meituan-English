package com.mtenglish.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.mtenglish.app.BuildConfig
import com.mtenglish.app.MtEnglishApp
import com.mtenglish.app.Prefs
import com.mtenglish.app.R
import com.mtenglish.app.databinding.ActivitySettingsBinding
import com.mtenglish.app.translate.TextKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale

/** Hosts [SettingsFragment] under a yellow toolbar. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings_title)
        binding.settingsToolbar.setTitleTextColor(getColor(R.color.mt_ink))
        binding.settingsToolbar.navigationIcon?.setTint(getColor(R.color.mt_ink))

        val toolbar = binding.settingsToolbar
        val container = binding.settingsContainer
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            container.updatePadding(left = bars.left, right = bars.right)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private var testJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setUpApiKey()
        setUpBaseUrl()
        setUpTestKey()
        setUpDownload()
        setUpClearCache()

        find<Preference>(KEY_ABOUT).summary = getString(R.string.pref_about_summary, BuildConfig.VERSION_NAME)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Keep the last rows clear of the gesture / navigation bar in edge-to-edge mode.
        val list: RecyclerView = listView
        list.clipToPadding = false
        val basePadding = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(bottom = basePadding + bars.bottom)
            insets
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                OfflineModelDownload.state.collect { renderDownload(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OfflineModelDownload.refresh()
        refreshCacheCount()
    }

    private inline fun <reified T : Preference> find(key: String): T =
        requireNotNull(findPreference<T>(key)) { "preferences.xml is missing \"$key\"" }

    // ---------------------------------------------------------------------------------------------

    private fun setUpApiKey() {
        val pref = find<EditTextPreference>(Prefs.KEY_API_KEY)
        pref.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            edit.isSingleLine = true
            edit.setSelection(edit.text.length)
        }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
            KeyMask.mask(p.text) ?: getString(R.string.pref_api_key_not_set)
        }
        pref.setOnPreferenceChangeListener { p, newValue ->
            val cleaned = (newValue as? String).orEmpty().trim().replace(Regex("\\s+"), "")
            if (cleaned != newValue) {
                // Store the cleaned key (pasted keys often carry spaces or line breaks).
                (p as EditTextPreference).text = cleaned
                false
            } else {
                true
            }
        }
    }

    private fun setUpBaseUrl() {
        val pref = find<EditTextPreference>(Prefs.KEY_BASE_URL)
        pref.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            edit.isSingleLine = true
            edit.setSelection(edit.text.length)
        }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
            p.text?.trim().orEmpty().ifEmpty { Prefs.DEFAULT_BASE_URL }
        }
        pref.setOnPreferenceChangeListener { p, newValue ->
            val raw = (newValue as? String).orEmpty().trim()
            when {
                raw.isEmpty() -> {
                    (p as EditTextPreference).text = Prefs.DEFAULT_BASE_URL
                    false
                }
                !isValidHttpsUrl(raw) -> {
                    Toast.makeText(requireContext(), R.string.pref_base_url_invalid, Toast.LENGTH_LONG).show()
                    false
                }
                raw.endsWith("/") -> {
                    (p as EditTextPreference).text = raw.trimEnd('/')
                    false
                }
                else -> true
            }
        }
    }

    private fun setUpTestKey() {
        val pref = find<Preference>(KEY_TEST)
        pref.setOnPreferenceClickListener {
            val ctx = requireContext().applicationContext
            val prefs = Prefs(ctx)
            if (prefs.claudeApiKey.isBlank()) {
                Toast.makeText(ctx, R.string.pref_test_key_missing, Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }
            if (testJob?.isActive == true) return@setOnPreferenceClickListener true
            pref.setSummary(R.string.pref_test_key_running)
            pref.isEnabled = false
            testJob = lifecycleScope.launch {
                val message = try {
                    // Ask Claude directly: the engine would answer this sample from the built-in
                    // dictionary without ever touching the key.
                    val english = MtEnglishApp.instance.engine.testClaude(SAMPLE, TextKind.MENU_ITEM)
                    if (english.isNullOrBlank()) {
                        getString(R.string.pref_test_key_failed)
                    } else {
                        getString(R.string.pref_test_key_ok, SAMPLE, english)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    getString(R.string.pref_test_key_failed)
                }
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                pref.summary = message
                pref.isEnabled = true
            }
            true
        }
    }

    private fun setUpDownload() {
        find<Preference>(KEY_DOWNLOAD).setOnPreferenceClickListener {
            OfflineModelDownload.start()
            true
        }
    }

    private var lastDownloadState: OfflineModelDownload.State? = null

    private fun renderDownload(state: OfflineModelDownload.State) {
        val pref = findPreference<Preference>(KEY_DOWNLOAD) ?: return
        val previous = lastDownloadState
        lastDownloadState = state
        pref.summary = getString(
            when (state) {
                OfflineModelDownload.State.Unknown -> R.string.home_offline_checking
                OfflineModelDownload.State.NotDownloaded -> R.string.home_offline_missing
                OfflineModelDownload.State.Downloading -> R.string.home_offline_downloading
                OfflineModelDownload.State.Ready -> R.string.home_offline_ready
                OfflineModelDownload.State.Failed -> R.string.home_offline_failed
            },
        )
        pref.isEnabled = state != OfflineModelDownload.State.Downloading && state != OfflineModelDownload.State.Ready
        if (state == OfflineModelDownload.State.Ready && previous == OfflineModelDownload.State.Downloading) {
            Toast.makeText(requireContext(), R.string.home_offline_done_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpClearCache() {
        val pref = find<Preference>(KEY_CLEAR_CACHE)
        pref.setOnPreferenceClickListener {
            val ctx = requireContext().applicationContext
            pref.isEnabled = false
            lifecycleScope.launch {
                try {
                    MtEnglishApp.instance.engine.clearCache()
                    Toast.makeText(ctx, R.string.pref_clear_cache_done, Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(ctx, R.string.home_cannot_open, Toast.LENGTH_SHORT).show()
                } finally {
                    pref.isEnabled = true
                }
                refreshCacheCount()
            }
            true
        }
    }

    private fun refreshCacheCount() {
        val pref = findPreference<Preference>(KEY_CLEAR_CACHE) ?: return
        lifecycleScope.launch {
            val count = try {
                MtEnglishApp.instance.engine.cachedCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                -1L
            }
            pref.summary = if (count >= 0) {
                getString(R.string.pref_clear_cache_summary, count.toInt()) + " · " + getString(R.string.pref_clear_cache_summary_plain)
            } else {
                getString(R.string.pref_clear_cache_summary_plain)
            }
        }
    }

    companion object {
        private const val KEY_TEST = "ui_test_api_key"
        private const val KEY_DOWNLOAD = "ui_download_offline_model"
        private const val KEY_CLEAR_CACHE = "ui_clear_cache"
        private const val KEY_ABOUT = "ui_about"
        private const val SAMPLE = "宫保鸡丁"

        internal fun isValidHttpsUrl(raw: String): Boolean = try {
            val uri = URI(raw)
            uri.scheme?.lowercase(Locale.ROOT) == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        } catch (_: Exception) {
            false
        }
    }
}
