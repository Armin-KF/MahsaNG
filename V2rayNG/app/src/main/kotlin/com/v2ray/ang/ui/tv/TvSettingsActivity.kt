package com.v2ray.ang.ui.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.preference.PreferenceFragmentCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.LogcatActivity
import com.v2ray.ang.ui.SettingsActivity
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SettingsViewModel

/** Every phone setting, grouped into a category rail. The phone's SettingsFragment does the work. */
class TvSettingsActivity : TvRailActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var selected: View? = null
    private var shownKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.screenTitle.setText(R.string.title_settings)
        binding.listArea.visibility = View.GONE
        binding.content.visibility = View.VISIBLE
        settingsViewModel.startListenPreferenceChange()

        val first = category("tv_general", R.string.tv_settings_general)
        category("tv_connection", R.string.tv_settings_connection)
        category("tv_routing", R.string.tv_settings_routing)
        category("tv_dns", R.string.tv_settings_dns)
        category("tv_advanced", R.string.tv_settings_advanced)
        category("tv_about", R.string.tv_settings_about)
        railAction(R.drawable.ic_logcat_white_24dp, R.string.tv_tile_logs) {
            startActivity(Intent(this, LogcatActivity::class.java))
        }
        first.requestFocus()
        show("tv_general")
    }

    private fun category(key: String, @StringRes title: Int): View {
        val item = railAction(0, title) {}
        // The remote picks a category by focusing it; a mouse or touch click never focuses, so clicking picks it too.
        item.setOnClickListener { select(item, key, 0); focusContent() }
        item.setOnFocusChangeListener { view, hasFocus -> if (hasFocus) select(view, key, 120) }
        return item
    }

    private fun select(view: View, key: String, delayMs: Long) {
        selected?.isActivated = false
        view.isActivated = true
        selected = view
        // Debounced: holding Down across the rail shouldn't inflate every category.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ show(key) }, delayMs)
    }

    private fun show(key: String) {
        if (key == shownKey) return
        shownKey = key
        val fragment = SettingsActivity.SettingsFragment().apply {
            arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key) }
        }
        supportFragmentManager.beginTransaction().replace(R.id.content, fragment).commit()
    }

    private fun focusContent() {
        binding.content.findFocus() ?: binding.content.requestFocus(View.FOCUS_FORWARD)
    }

    /** Back steps out of the settings pane to the rail before leaving the screen. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.content.hasFocus()) selected?.requestFocus() else super.onBackPressed()
    }

    /** Called through android:onClick in preference_with_help_link. */
    @Suppress("unused")
    fun onModeHelpClicked(view: View) {
        Utils.openUri(this, AppConfig.v2rayNGWikiMode)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
