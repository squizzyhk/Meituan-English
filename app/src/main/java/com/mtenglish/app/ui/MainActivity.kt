package com.mtenglish.app.ui

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.mtenglish.app.MtEnglishApp
import com.mtenglish.app.Prefs
import com.mtenglish.app.R
import com.mtenglish.app.databinding.ActivityMainBinding
import com.mtenglish.app.databinding.ItemHomeStepBinding
import com.mtenglish.app.databinding.ItemSiteTileBinding
import com.mtenglish.app.web.BrowserActivity
import kotlinx.coroutines.launch

/** Home screen: translation status, English site shortcuts, and live-overlay setup guide. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var lastOfflineState: OfflineModelDownload.State? = null

    private data class Site(
        @StringRes val title: Int,
        @StringRes val subtitle: Int,
        @DrawableRes val icon: Int,
        val url: String,
    )

    private val sites = listOf(
        Site(R.string.home_site_food, R.string.home_site_food_sub, R.drawable.ui_ic_food, URL_FOOD),
        Site(R.string.home_site_home, R.string.home_site_home_sub, R.drawable.ui_ic_deals, URL_MEITUAN_HOME),
        Site(R.string.home_site_reviews, R.string.home_site_reviews_sub, R.drawable.ui_ic_reviews, URL_DIANPING),
        Site(R.string.home_site_hotels, R.string.home_site_hotels_sub, R.drawable.ui_ic_hotel, URL_HOTELS),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // The header is always yellow, so status bar icons stay dark in both light and dark mode.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        applyInsets()

        setSupportActionBar(binding.homeToolbar)

        buildSiteTiles()
        setUpLinkField()
        setUpStatusCard()
        setUpSteps()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                OfflineModelDownload.state.collect { renderOffline(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderEngine()
        renderSteps()
        OfflineModelDownload.refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ui_main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.ui_action_settings -> {
            openSettings()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------------------------------------
    // Layout

    private fun applyInsets() {
        val header = binding.homeHeader
        val headerTop = header.paddingTop
        val scroll = binding.homeScroll
        val content = binding.homeContent
        val contentBottom = content.paddingBottom
        val contentStart = content.paddingStart
        val contentEnd = content.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.updatePadding(top = headerTop + bars.top, left = bars.left, right = bars.right)
            content.updatePadding(
                left = contentStart + bars.left,
                right = contentEnd + bars.right,
                bottom = contentBottom + maxOf(bars.bottom, ime.bottom),
            )
            scroll.clipToPadding = false
            insets
        }
    }

    private fun buildSiteTiles() {
        val grid = binding.homeSiteGrid
        grid.removeAllViews()
        val inflater = LayoutInflater.from(this)
        sites.forEachIndexed { index, site ->
            val tile = ItemSiteTileBinding.inflate(inflater, grid, false)
            tile.tileIcon.setImageResource(site.icon)
            tile.tileTitle.setText(site.title)
            tile.tileSubtitle.setText(site.subtitle)
            tile.root.contentDescription = getString(site.title) + ". " + getString(site.subtitle)
            tile.root.setOnClickListener { openBrowser(site.url, getString(site.title)) }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(index / 2),
                GridLayout.spec(index % 2, 1f),
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                val m = (4 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            }
            grid.addView(tile.root, lp)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Translation status

    private fun setUpStatusCard() {
        binding.homeEngineButton.setOnClickListener { openSettings() }
        binding.homeOfflineButton.setOnClickListener { OfflineModelDownload.start() }
    }

    private fun renderEngine() {
        val claude = MtEnglishApp.instance.engine.isClaudeConfigured()
        if (claude) {
            binding.homeEngineTitle.setText(R.string.home_engine_claude)
            binding.homeEngineDetail.text = getString(R.string.home_engine_claude_detail, modelDisplayName(prefs.claudeModel))
            binding.homeEngineButton.setText(R.string.home_change_api_key)
            binding.homeEngineButton.contentDescription = getString(R.string.pref_api_key_title) + ": " + getString(R.string.home_change_api_key)
        } else {
            binding.homeEngineTitle.setText(R.string.home_engine_basic)
            binding.homeEngineDetail.setText(R.string.home_engine_basic_detail)
            binding.homeEngineButton.setText(R.string.home_add_api_key)
            binding.homeEngineButton.contentDescription = null
        }
    }

    private fun modelDisplayName(model: String): String {
        val values = resources.getStringArray(R.array.pref_model_values)
        val entries = resources.getStringArray(R.array.pref_model_entries)
        val i = values.indexOf(model)
        return if (i >= 0) entries[i].substringBefore(" —").trim() else model
    }

    private fun renderOffline(state: OfflineModelDownload.State) {
        val previous = lastOfflineState
        lastOfflineState = state
        val b = binding
        b.homeOfflineProgress.visibility = if (state == OfflineModelDownload.State.Downloading) View.VISIBLE else View.GONE
        b.homeOfflineCheck.visibility = if (state == OfflineModelDownload.State.Ready) View.VISIBLE else View.GONE
        when (state) {
            OfflineModelDownload.State.Unknown -> {
                b.homeOfflineStatus.setText(R.string.home_offline_checking)
                b.homeOfflineButton.visibility = View.GONE
            }
            OfflineModelDownload.State.NotDownloaded -> {
                b.homeOfflineStatus.setText(R.string.home_offline_missing)
                b.homeOfflineButton.visibility = View.VISIBLE
                b.homeOfflineButton.isEnabled = true
                b.homeOfflineButton.setText(R.string.home_offline_download)
            }
            OfflineModelDownload.State.Downloading -> {
                b.homeOfflineStatus.setText(R.string.home_offline_downloading)
                b.homeOfflineButton.visibility = View.VISIBLE
                b.homeOfflineButton.isEnabled = false
            }
            OfflineModelDownload.State.Ready -> {
                b.homeOfflineStatus.setText(R.string.home_offline_ready)
                b.homeOfflineButton.visibility = View.GONE
                if (previous == OfflineModelDownload.State.Downloading) {
                    Toast.makeText(this, R.string.home_offline_done_toast, Toast.LENGTH_SHORT).show()
                }
            }
            OfflineModelDownload.State.Failed -> {
                b.homeOfflineStatus.setText(R.string.home_offline_failed)
                b.homeOfflineButton.visibility = View.VISIBLE
                b.homeOfflineButton.isEnabled = true
                b.homeOfflineButton.setText(R.string.home_offline_retry)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // "Open any Meituan link"

    private fun setUpLinkField() {
        val input = binding.homeLinkInput
        binding.homeLinkLayout.setEndIconOnClickListener { pasteFromClipboard() }
        input.doAfterTextChanged { binding.homeLinkLayout.error = null }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openPastedLink()
                true
            } else {
                false
            }
        }
        // Multi-line input ignores imeOptions unless we force the action button.
        input.setRawInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI)
        input.imeOptions = EditorInfo.IME_ACTION_GO
        binding.homeLinkOpen.setOnClickListener { openPastedLink() }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.home_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // Share text is usually long Chinese prose; show just the extracted link when there is one.
        val shown = (MeituanLinks.parse(text) as? MeituanLinks.Result.Ok)?.url ?: text.trim()
        binding.homeLinkInput.setText(shown)
        binding.homeLinkInput.setSelection(shown.length)
    }

    private fun openPastedLink() {
        val raw = binding.homeLinkInput.text?.toString().orEmpty()
        val layout = binding.homeLinkLayout
        if (raw.isBlank()) {
            layout.error = getString(R.string.home_link_error_empty)
            return
        }
        when (val r = MeituanLinks.parse(raw)) {
            is MeituanLinks.Result.Ok -> {
                layout.error = null
                hideKeyboard()
                openBrowser(r.url, getString(R.string.home_link_title_default))
            }
            is MeituanLinks.Result.NotMeituan -> layout.error = getString(R.string.home_link_error_not_meituan, r.host)
            is MeituanLinks.Result.NotWeb -> layout.error = getString(R.string.home_link_error_not_web)
            MeituanLinks.Result.NoLink -> layout.error = getString(R.string.home_link_error_none)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.homeLinkInput.windowToken, 0)
        binding.homeLinkInput.clearFocus()
    }

    // ---------------------------------------------------------------------------------------------
    // Official app + live overlay steps

    private fun setUpSteps() {
        stepNumber(binding.homeStep1, 1, R.string.home_step1_title)
        stepNumber(binding.homeStep2, 2, R.string.home_step2_title)
        stepNumber(binding.homeStep3, 3, R.string.home_step3_title)
        binding.homeStep3.stepDesc.setText(R.string.home_step3_desc)
        binding.homeStep3.stepButton.setText(R.string.home_step3_button)

        binding.homeStep1.stepButton.setOnClickListener {
            if (OfficialApps.launchFirstInstalled(this) == null && !OfficialApps.openDownloadPage(this)) {
                Toast.makeText(this, R.string.home_cannot_open, Toast.LENGTH_SHORT).show()
            }
        }
        binding.homeStep2.stepButton.setOnClickListener {
            if (!AccessibilityStatus.openSettings(this)) {
                Toast.makeText(this, R.string.home_cannot_open, Toast.LENGTH_SHORT).show()
            }
        }
        binding.homeStep3.stepButton.setOnClickListener {
            if (OfficialApps.launchFirstInstalled(this) == null) {
                Toast.makeText(this, R.string.home_no_app_installed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stepNumber(step: ItemHomeStepBinding, n: Int, @StringRes title: Int) {
        step.stepNumber.text = String.format(java.util.Locale.getDefault(), "%d", n)
        step.stepTitle.text = getString(title)
    }

    private fun renderSteps() {
        val installed = OfficialApps.installed(this)
        val s1 = binding.homeStep1
        setDone(s1, installed.isNotEmpty())
        if (installed.isEmpty()) {
            s1.stepDesc.setText(R.string.home_step1_missing)
            s1.stepButton.setText(R.string.home_step1_get)
        } else {
            s1.stepDesc.text = getString(R.string.home_step1_installed, installed.joinToString(", ") { it.englishName })
            s1.stepButton.text = getString(R.string.home_step1_open, installed.first().englishName)
        }
        styleButton(s1.stepButton, primary = installed.isEmpty())

        val enabled = AccessibilityStatus.isEnabled(this)
        val s2 = binding.homeStep2
        setDone(s2, enabled)
        s2.stepDesc.setText(
            when {
                !enabled -> R.string.home_step2_off
                prefs.overlayEnabled -> R.string.home_step2_on
                else -> R.string.home_step2_paused
            },
        )
        s2.stepButton.setText(if (enabled) R.string.home_step2_button_on else R.string.home_step2_button)
        styleButton(s2.stepButton, primary = installed.isNotEmpty() && !enabled)

        val s3 = binding.homeStep3
        val ready = installed.isNotEmpty() && enabled
        s3.stepButton.isEnabled = installed.isNotEmpty()
        styleButton(s3.stepButton, primary = ready)
    }

    private fun setDone(step: ItemHomeStepBinding, done: Boolean) {
        step.stepNumber.visibility = if (done) View.INVISIBLE else View.VISIBLE
        step.stepCheck.visibility = if (done) View.VISIBLE else View.GONE
    }

    /** Highlights the next thing to do with a filled yellow button; other steps get a tonal one. */
    private fun styleButton(button: MaterialButton, primary: Boolean) {
        val bg = if (primary) R.color.mt_yellow else R.color.ui_secondary_container
        val fg = if (primary) R.color.mt_ink else R.color.ui_on_secondary_container
        button.backgroundTintList = getColorStateListCompat(bg)
        button.setTextColor(getColorStateListCompat(fg))
        button.alpha = if (button.isEnabled) 1f else 0.5f
    }

    private fun getColorStateListCompat(id: Int) = ContextCompat.getColorStateList(this, id)

    // ---------------------------------------------------------------------------------------------
    // Navigation

    private fun openBrowser(url: String, title: String) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_URL, url)
                .putExtra(BrowserActivity.EXTRA_TITLE, title),
        )
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    companion object {
        const val URL_FOOD = "https://h5.waimai.meituan.com/waimai/mindex/home"
        const val URL_MEITUAN_HOME = "https://i.meituan.com/"
        const val URL_DIANPING = "https://m.dianping.com/"
        const val URL_HOTELS = "https://i.meituan.com/awp/h5/hotel/search/search.html"
    }
}
