package com.v2ray.ang.ui.tv

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityTvDashboardBinding
import com.v2ray.ang.databinding.ItemTvTileBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.ui.BaseActivity
import com.v2ray.ang.ui.ConfigImporter
import com.v2ray.ang.ui.LogcatActivity
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.SpeedtestUtil

class TvDashboardActivity : BaseActivity() {
    private companion object {
        const val AMBIENT_ALPHA_IDLE = 18
        const val AMBIENT_ALPHA_ACTIVE = 46
    }

    private lateinit var binding: ActivityTvDashboardBinding
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }
    private var ambientAnimator: ValueAnimator? = null
    private var ambientColor = Color.TRANSPARENT
    private var stateDetail = ""
    private var currentTest: TvConnection.Test? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) TvConnection.connect() else TvConnection.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TvConnection.attach(this)
        ConfigImporter.prepareFirstRun(this)

        binding.version.text = "v${BuildConfig.VERSION_NAME}  ·  ${SpeedtestUtil.getLibVersion()}"
        binding.btnConnect.setOnClickListener { onPrimaryAction() }
        binding.cardActive.setOnClickListener { openServers() }

        tile(binding.tileServers, R.drawable.ic_tv_servers, R.string.tv_tile_servers) { openServers() }
        tile(binding.tileSubscriptions, R.drawable.ic_subscriptions_white_24dp, R.string.tv_tile_subscriptions) {
            startActivity(Intent(this, TvSubscriptionsActivity::class.java))
        }
        tile(binding.tileAdd, R.drawable.ic_add_white_24dp, R.string.tv_tile_add) {
            startActivity(Intent(this, TvServersActivity::class.java).putExtra(TvServersActivity.EXTRA_OPEN_ADD, true))
        }
        tile(binding.tileTest, R.drawable.ic_tv_speed, R.string.tv_tile_test) {
            if (selectedConfig() == null) openServers() else TvConnection.testSelected()
        }
        tile(binding.tileSettings, R.drawable.ic_settings_white_24dp, R.string.tv_tile_settings) { openSettings() }
        tile(binding.tileLogs, R.drawable.ic_logcat_white_24dp, R.string.tv_tile_logs) {
            startActivity(Intent(this, LogcatActivity::class.java))
        }

        TvConnection.state.observe(this) { render(it) }
        TvConnection.test.observe(this) { renderTest(it) }
        TvConnection.results.observe(this) {
            renderActiveConfig()
            renderTileHints()
        }
    }

    override fun onResume() {
        super.onResume()
        TvConnection.probe()
        renderActiveConfig()
        renderTileHints()
        render(TvConnection.state.value ?: TvConnection.State.DISCONNECTED)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> openSettings()
            // Leaves the VPN running in its foreground service, same as the phone screen.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> moveTaskToBack(false)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun tile(tile: ItemTvTileBinding, @DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
        tile.icon.setImageResource(icon)
        tile.label.setText(label)
        tile.root.setOnClickListener { onClick() }
    }

    /** Each tile says something true about the app right now, so the grid reads as a status board. */
    private fun renderTileHints() {
        binding.tileServers.hint.text = getString(R.string.tv_tile_servers_hint, MmkvManager.decodeServerList().size)
        val subs = MmkvManager.decodeSubscriptions().size
        binding.tileSubscriptions.hint.text =
            if (subs == 0) getString(R.string.tv_tile_subs_none) else resources.getQuantityString(R.plurals.tv_tile_subs_count, subs, subs)
        binding.tileAdd.hint.setText(R.string.tv_tile_add_hint)
        val guid = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).orEmpty()
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
        binding.tileTest.hint.text = when {
            delay > 0 -> getString(R.string.tv_tile_test_hint, getString(R.string.tv_latency_ms, delay))
            delay < 0 -> getString(R.string.tv_tile_test_hint, getString(R.string.tv_latency_failed))
            else -> getString(R.string.tv_tile_test_untested)
        }
        val vpnMode = (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN") == "VPN"
        binding.tileSettings.hint.setText(if (vpnMode) R.string.tv_tile_settings_vpn else R.string.tv_tile_settings_proxy)
        binding.tileLogs.hint.setText(R.string.tv_tile_logs_hint)
    }

    private fun openServers() = startActivity(Intent(this, TvServersActivity::class.java))

    private fun openSettings() = startActivity(Intent(this, TvSettingsActivity::class.java))

    private fun selectedConfig() =
        MmkvManager.decodeServerConfig(mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).orEmpty())

    private fun onPrimaryAction() {
        when (TvConnection.state.value) {
            TvConnection.State.CONNECTING, TvConnection.State.DISCONNECTING -> Unit
            TvConnection.State.CONNECTED -> TvConnection.disconnect()
            else -> when {
                MmkvManager.decodeServerList().isEmpty() ->
                    startActivity(Intent(this, TvServersActivity::class.java).putExtra(TvServersActivity.EXTRA_OPEN_ADD, true))
                selectedConfig() == null -> openServers()
                else -> {
                    val vpnMode = (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN") == "VPN"
                    val permission = if (vpnMode) VpnService.prepare(this) else null
                    if (permission == null) TvConnection.connect() else requestVpnPermission.launch(permission)
                }
            }
        }
    }

    private fun render(state: TvConnection.State) {
        val colorRes: Int
        val title: Int
        val detail: String
        when (state) {
            TvConnection.State.CONNECTED -> {
                // The tunnel being up says nothing about the server behind it; a failed test does.
                val unreachable = (currentTest as? TvConnection.Test.Done)?.ok == false
                colorRes = if (unreachable) R.color.tv_status_error else R.color.tv_accent
                title = if (unreachable) R.string.tv_status_unreachable else R.string.tv_status_connected
                detail = getString(if (unreachable) R.string.tv_detail_unreachable else R.string.tv_detail_connected)
            }
            TvConnection.State.CONNECTING -> {
                colorRes = R.color.tv_status_busy
                title = R.string.tv_status_connecting
                detail = getString(R.string.tv_detail_connecting)
            }
            TvConnection.State.DISCONNECTING -> {
                colorRes = R.color.tv_status_busy
                title = R.string.tv_status_disconnecting
                detail = getString(R.string.tv_detail_disconnecting)
            }
            TvConnection.State.ERROR -> {
                colorRes = R.color.tv_status_error
                title = R.string.tv_status_error
                detail = getString(TvConnection.errorRes)
            }
            TvConnection.State.DISCONNECTED -> {
                colorRes = R.color.tv_status_off
                title = R.string.tv_status_disconnected
                detail = getString(R.string.tv_detail_disconnected)
            }
        }
        val color = ContextCompat.getColor(this, colorRes)
        val busy = state == TvConnection.State.CONNECTING || state == TvConnection.State.DISCONNECTING
        binding.statusOrb.setState(color, when {
            busy -> TvStatusOrb.Mode.BUSY
            state == TvConnection.State.CONNECTED -> TvStatusOrb.Mode.LIVE
            else -> TvStatusOrb.Mode.IDLE
        })
        binding.statusOverline.setTextColor(color)
        setAmbient(color, if (state == TvConnection.State.DISCONNECTED) AMBIENT_ALPHA_IDLE else AMBIENT_ALPHA_ACTIVE)
        binding.statusTitle.setText(title)
        stateDetail = detail
        renderDetail()

        // Keep the button enabled while busy: disabling a focused view would drop remote focus.
        binding.btnConnect.alpha = if (busy) 0.6f else 1f
        binding.btnConnect.setText(primaryLabel(state))
        // Connecting is the action to invite; disconnecting shouldn't look like the thing to press.
        binding.btnConnect.isActivated = state == TvConnection.State.CONNECTED || state == TvConnection.State.DISCONNECTING
    }

    @StringRes
    private fun primaryLabel(state: TvConnection.State): Int = when {
        state == TvConnection.State.CONNECTING -> R.string.tv_status_connecting
        state == TvConnection.State.DISCONNECTING -> R.string.tv_status_disconnecting
        state == TvConnection.State.CONNECTED || state == TvConnection.State.DISCONNECTING -> R.string.tv_action_disconnect
        MmkvManager.decodeServerList().isEmpty() -> R.string.tv_action_add_config
        selectedConfig() == null -> R.string.tv_action_choose_server
        else -> R.string.tv_action_connect
    }

    /** A wash of the status color from behind the hero panel; it eases between states rather than snapping. */
    private fun setAmbient(color: Int, alpha: Int) {
        val target = ColorUtils.setAlphaComponent(color, alpha)
        if (target == ambientColor) return
        ambientAnimator?.cancel()
        ambientAnimator = ValueAnimator.ofArgb(ambientColor, target).apply {
            duration = resources.getInteger(R.integer.tv_anim_status).toLong() * 3
            addUpdateListener { paintAmbient(it.animatedValue as Int) }
            start()
        }
    }

    private fun paintAmbient(color: Int) {
        ambientColor = color
        val view = binding.ambient
        if (view.width == 0) {
            view.post { paintAmbient(color) }
            return
        }
        view.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            setGradientCenter(if (rtl) 0.75f else 0.25f, 0.5f)
            gradientRadius = view.width * 0.55f
            colors = intArrayOf(color, ColorUtils.setAlphaComponent(color, 0))
        }
    }

    private fun renderActiveConfig() {
        val guid = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).orEmpty()
        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            binding.activeName.setText(R.string.tv_no_config)
            binding.activeType.visibility = View.GONE
            binding.activeAddress.setText(R.string.tv_no_config_hint)
            binding.activeLatency.visibility = View.GONE
            return
        }
        binding.activeName.text = config.remarks
        binding.activeType.visibility = View.VISIBLE
        binding.activeType.text = if (config.configType == EConfigType.CUSTOM)
            getString(R.string.server_customize_config) else config.configType.name
        val outbound = config.getProxyOutbound()
        binding.activeAddress.text = if (outbound?.tag == TvFormat.SERVERLESS_TAG) getString(R.string.tv_direct)
            else TvFormat.endpoint(outbound?.getServerAddress(), outbound?.getServerPort())
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
        binding.activeLatency.visibility = if (delay == 0L) View.GONE else View.VISIBLE
        binding.activeLatency.text = "● " + if (delay > 0) getString(R.string.tv_latency_ms, delay) else getString(R.string.tv_latency_failed)
        binding.activeLatency.setTextColor(ContextCompat.getColorStateList(this, latencyColorRes(delay)))
    }

    private fun renderTest(test: TvConnection.Test?) {
        currentTest = test
        render(TvConnection.state.value ?: TvConnection.State.DISCONNECTED)
    }

    /** The line under the status shows the latest test when there is one; it answers "is it actually working?". */
    private fun renderDetail() {
        when (val test = currentTest) {
            null -> {
                binding.statusDetail.text = stateDetail
                binding.statusDetail.setTextColor(ContextCompat.getColor(this, R.color.tv_text_secondary_raw))
            }
            is TvConnection.Test.Running -> {
                binding.statusDetail.setText(R.string.tv_test_testing)
                binding.statusDetail.setTextColor(ContextCompat.getColor(this, R.color.tv_status_busy))
            }
            is TvConnection.Test.Done -> {
                binding.statusDetail.text = if (test.ok) test.message else stateDetail
                binding.statusDetail.setTextColor(ContextCompat.getColor(this, if (test.ok) R.color.tv_accent else R.color.tv_status_error))
            }
        }
    }

}
