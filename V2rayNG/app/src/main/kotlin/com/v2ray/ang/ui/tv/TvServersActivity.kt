package com.v2ray.ang.ui.tv

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemTvServerBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.ConfigImporter
import com.v2ray.ang.ui.ServerActivity
import com.v2ray.ang.ui.ServerCustomConfigActivity
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.viewmodel.MainViewModel

class TvServersActivity : TvRailActivity() {
    companion object {
        const val EXTRA_OPEN_ADD = "open_add"
    }

    private val viewModel: MainViewModel by viewModels()
    private val importer = ConfigImporter(this) { viewModel }
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.screenTitle.setText(R.string.tv_tile_servers)
        binding.screenHint.setText(R.string.tv_hint_rows)

        railAction(R.drawable.ic_add_white_24dp, R.string.tv_action_add_config) { showAddDialog() }
        railAction(R.drawable.ic_tv_speed, R.string.tv_rail_test_all) { viewModel.testAllRealPing() }
        railAction(R.drawable.ic_whatshot_white_24dp, R.string.tv_rail_tcp_all) { viewModel.testAllTcping() }
        railAction(R.drawable.ic_tv_sort, R.string.tv_rail_sort) {
            MmkvManager.sortByTestResults()
            viewModel.reloadServerList()
        }
        railAction(R.drawable.ic_outline_filter_alt_white_24, R.string.tv_rail_filter) { viewModel.filterConfig(this) }
        railAction(R.drawable.ic_settings_white_24dp, R.string.tv_rail_manage) { showManageDialog() }

        setupList(adapter)
        viewModel.startListenBroadcast()
        viewModel.updateListAction.observe(this) { index ->
            if (index >= 0) adapter.notifyItemChanged(index) else adapter.notifyDataSetChanged()
            renderSummary()
        }
        TvConnection.results.observe(this) { guid ->
            viewModel.getPosition(guid).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
        }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) showAddDialog()
    }

    override fun onResume() {
        super.onResume()
        val firstShow = binding.list.adapter?.itemCount == 0
        viewModel.reloadServerList()
        if (firstShow) focusActiveRow()
    }

    private fun renderSummary() {
        val count = viewModel.serversCache.size
        binding.screenSubtitle.text = getString(R.string.tv_servers_count, count)
        val filtered = viewModel.subscriptionId.isNotEmpty() || viewModel.keywordFilter.isNotEmpty()
        when {
            count > 0 -> showEmpty(false)
            filtered -> showEmpty(true, getString(R.string.tv_servers_empty_title), getString(R.string.tv_servers_empty_filtered),
                R.string.tv_rail_filter) { viewModel.filterConfig(this) }
            else -> showEmpty(true, getString(R.string.tv_servers_empty_title), getString(R.string.tv_servers_empty_hint),
                R.string.tv_action_add_config) { showAddDialog() }
        }
    }

    /** Opening the screen lands on the server in use, so changing it is one press away. */
    private fun focusActiveRow() {
        val selected = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).orEmpty()
        val position = viewModel.getPosition(selected).coerceAtLeast(0)
        if (viewModel.serversCache.isEmpty()) {
            binding.root.post { (if (binding.emptyAction.visibility == View.VISIBLE) binding.emptyAction else binding.rail.getChildAt(0))?.requestFocus() }
            return
        }
        focusRow(position)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val holder = currentFocus?.let { binding.list.findContainingViewHolder(it) }
            val guid = holder?.bindingAdapterPosition?.let { viewModel.serversCache.getOrNull(it)?.guid }
            if (guid != null) showRowOptions(guid) else showManageDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun select(guid: String) {
        val previous = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)
        if (guid == previous) return
        mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, guid)
        previous?.let { p -> viewModel.getPosition(p).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) } }
        viewModel.getPosition(guid).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
        TvConnection.restart()
    }

    private fun showRowOptions(guid: String) {
        val config = MmkvManager.decodeServerConfig(guid) ?: return
        val custom = config.configType == EConfigType.CUSTOM
        val options = mutableListOf(
            TvOption(R.string.tv_option_select) { select(guid) },
            TvOption(R.string.tv_option_test) { TvConnection.measure(guid) },
            TvOption(R.string.tv_option_edit) { edit(guid, custom) },
        )
        if (!custom) {
            options += TvOption(R.string.tv_option_share_qr) { importer.showQr(AngConfigManager.share2QRCode(guid)) }
            options += TvOption(R.string.tv_option_copy) { reportCopy(AngConfigManager.share2Clipboard(this, guid)) }
        }
        options += TvOption(R.string.tv_option_copy_full) { reportCopy(AngConfigManager.shareFullContent2Clipboard(this, guid)) }
        options += TvOption(R.string.tv_option_delete, destructive = true) { delete(guid) }
        TvDialogs.options(this, config.remarks, options)
    }

    private fun reportCopy(result: Int) = toast(if (result == 0) R.string.toast_success else R.string.toast_failure)

    private fun edit(guid: String, custom: Boolean) {
        val target = if (custom) ServerCustomConfigActivity::class.java else ServerActivity::class.java
        startActivity(Intent(this, target).putExtra("guid", guid).putExtra("isRunning", TvConnection.isConnected))
    }

    private fun delete(guid: String) {
        if (guid == mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)) {
            AlertDialog.Builder(this).setMessage(R.string.tv_delete_active_blocked).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        TvDialogs.confirm(this, getString(R.string.del_config_comfirm), R.string.tv_option_delete) {
            val position = viewModel.getPosition(guid)
            viewModel.removeServer(guid)
            adapter.notifyItemRemoved(position)
            renderSummary()
            focusRowAfterRemoval(position)
        }
    }

    private fun showAddDialog() {
        val options = mutableListOf(
            TvOption(R.string.tv_add_link) {
                TvDialogs.input(this, R.string.tv_add_link, R.string.tv_add_link_hint) { importer.importBatchConfig(it) }
            },
            TvOption(R.string.tv_add_sub_link) {
                TvDialogs.input(this, R.string.tv_add_sub_link, R.string.tv_add_url_hint) { importer.importBatchConfig(it) }
            },
            TvOption(R.string.menu_item_import_config_clipboard) { importer.importClipboard() },
        )
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            options += TvOption(R.string.menu_item_import_config_qrcode) { importer.importQRcode(true) }
        }
        options += TvOption(R.string.menu_item_import_config_manually_vless) { importer.importManually(EConfigType.VLESS.value) }
        options += TvOption(R.string.menu_item_import_config_manually_vmess) { importer.importManually(EConfigType.VMESS.value) }
        options += TvOption(R.string.menu_item_import_config_manually_trojan) { importer.importManually(EConfigType.TROJAN.value) }
        options += TvOption(R.string.menu_item_import_config_manually_ss) { importer.importManually(EConfigType.SHADOWSOCKS.value) }
        options += TvOption(R.string.menu_item_import_config_manually_socks) { importer.importManually(EConfigType.SOCKS.value) }
        options += TvOption(R.string.menu_item_import_config_manually_wireguard) { importer.importManually(EConfigType.WIREGUARD.value) }
        options += TvOption(R.string.tv_add_custom_url) {
            TvDialogs.input(this, R.string.tv_add_custom_url, R.string.tv_add_url_hint) { importer.importConfigCustomUrl(it) }
        }
        options += TvOption(R.string.menu_item_import_config_custom_clipboard) { importer.importConfigCustomClipboard() }
        options += TvOption(R.string.menu_item_import_config_custom_local) { importer.importConfigCustomLocal() }
        TvDialogs.options(this, getString(R.string.tv_add_title), options)
    }

    private fun showManageDialog() {
        TvDialogs.options(this, getString(R.string.tv_rail_manage), listOf(
            TvOption(R.string.title_sub_update) { importer.importConfigViaSub() },
            TvOption(R.string.title_export_all) {
                reportCopy(AngConfigManager.shareNonCustomConfigsToClipboard(this, viewModel.serverList))
            },
            TvOption(R.string.title_service_restart) { TvConnection.restart() },
            TvOption(R.string.title_del_duplicate_config, destructive = true) { viewModel.removeDuplicateServer() },
            TvOption(R.string.title_del_invalid_config, destructive = true) {
                TvDialogs.confirm(this, getString(R.string.del_config_comfirm), R.string.tv_option_delete) {
                    MmkvManager.removeInvalidServer()
                    viewModel.reloadServerList()
                }
            },
            TvOption(R.string.title_del_all_config, destructive = true) {
                TvDialogs.confirm(this, getString(R.string.del_config_comfirm), R.string.tv_option_delete) {
                    MmkvManager.removeAllServer()
                    viewModel.reloadServerList()
                }
            },
        ))
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val row: ItemTvServerBinding) : RecyclerView.ViewHolder(row.root)

        init {
            // Keeps focus on the same row across notifyDataSetChanged.
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) = viewModel.serversCache[position].guid.hashCode().toLong()

        override fun getItemCount() = viewModel.serversCache.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTvServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = viewModel.serversCache[position]
            val row = holder.row
            val active = item.guid == mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)
            row.name.text = item.config.remarks
            row.indicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
            row.badgeActive.visibility = if (active) View.VISIBLE else View.GONE
            row.type.text = if (item.config.configType == EConfigType.CUSTOM)
                getString(R.string.server_customize_config) else item.config.configType.name

            val outbound = item.config.getProxyOutbound()
            val sub = subStorage?.decodeString(item.config.subscriptionId)
                ?.takeIf { it.isNotBlank() }?.let { Gson().fromJson(it, SubscriptionItem::class.java).remarks }
            row.meta.text = listOfNotNull(sub, TvFormat.endpoint(outbound?.getServerAddress(), outbound?.getServerPort()).takeIf { it.isNotEmpty() }).joinToString("  ·  ")

            val delay = MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: 0L
            row.latency.visibility = if (delay == 0L) View.GONE else View.VISIBLE
            row.latency.text = "● " + if (delay > 0) getString(R.string.tv_latency_ms, delay) else getString(R.string.tv_latency_failed)
            row.latency.setTextColor(ContextCompat.getColorStateList(this@TvServersActivity, latencyColorRes(delay)))
            row.rowMain.isActivated = active

            row.rowMain.setOnClickListener { select(item.guid) }
            row.rowMain.setOnLongClickListener { showRowOptions(item.guid); true }
            (row.btnMore as ImageView).setOnClickListener { showRowOptions(item.guid) }
        }
    }
}
