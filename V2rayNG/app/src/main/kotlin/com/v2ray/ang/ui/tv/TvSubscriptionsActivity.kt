package com.v2ray.ang.ui.tv

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemTvSubscriptionBinding
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.ConfigImporter
import com.v2ray.ang.ui.SubEditActivity
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel

class TvSubscriptionsActivity : TvRailActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val importer = ConfigImporter(this) { viewModel }
    private val subStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val adapter = Adapter()
    private var subscriptions: List<Pair<String, SubscriptionItem>> = emptyList()
    private val refreshing = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.screenTitle.setText(R.string.tv_subs_title)
        railAction(R.drawable.ic_tv_refresh, R.string.tv_subs_refresh_all) { refreshAll() }
        railAction(R.drawable.ic_add_white_24dp, R.string.tv_subs_add) { add() }
        setupList(adapter)
    }

    override fun onResume() {
        super.onResume()
        val firstShow = subscriptions.isEmpty()
        load()
        if (firstShow) {
            if (subscriptions.isEmpty()) binding.emptyAction.requestFocus() else focusRow(0)
        }
    }

    private fun load() {
        subscriptions = MmkvManager.decodeSubscriptions()
        adapter.notifyDataSetChanged()
        showEmpty(subscriptions.isEmpty(), getString(R.string.tv_subs_empty_title), getString(R.string.tv_subs_empty_hint), R.string.tv_subs_add) { add() }
    }

    private fun add() = startActivity(Intent(this, SubEditActivity::class.java))

    private fun refreshAll() {
        val eligible = subscriptions.filter { it.second.enabled && it.second.url.isNotBlank() }
        if (eligible.isEmpty()) toast(R.string.tv_nothing_to_refresh)
        eligible.forEach { refresh(it.first, it.second) }
    }

    private fun refresh(id: String, item: SubscriptionItem) {
        if (!refreshing.add(id)) return
        notifyRow(id)
        importer.refreshSubscription(id, item) {
            refreshing.remove(id)
            load()
        }
    }

    private fun notifyRow(id: String) {
        subscriptions.indexOfFirst { it.first == id }.takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            currentFocus?.let { binding.list.findContainingViewHolder(it) }?.bindingAdapterPosition
                ?.let { subscriptions.getOrNull(it) }?.let { showOptions(it.first, it.second) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showOptions(id: String, item: SubscriptionItem) {
        val options = mutableListOf<TvOption>()
        if (item.url.isNotBlank()) options += TvOption(R.string.tv_option_refresh) { refresh(id, item) }
        options += TvOption(if (item.enabled) R.string.tv_option_disable else R.string.tv_option_enable) {
            item.enabled = !item.enabled
            subStorage?.encode(id, Gson().toJson(item))
            load()
        }
        options += TvOption(R.string.tv_option_edit) {
            startActivity(Intent(this, SubEditActivity::class.java).putExtra("subId", id))
        }
        if (item.url.isNotBlank()) {
            options += TvOption(R.string.tv_option_share_qr) { importer.showQr(QRCodeDecoder.createQRCode(item.url)) }
            options += TvOption(R.string.tv_option_copy) {
                Utils.setClipboard(this, item.url)
                toast(R.string.toast_success)
            }
        }
        options += TvOption(R.string.tv_option_delete, destructive = true) {
            TvDialogs.confirm(this, getString(R.string.del_config_comfirm), R.string.tv_option_delete) {
                val position = subscriptions.indexOfFirst { it.first == id }
                MmkvManager.removeSubscription(id)
                load()
                focusRowAfterRemoval(position)
            }
        }
        TvDialogs.options(this, item.remarks, options)
    }

    private fun failureText(code: String) = getString(R.string.tv_sub_failed, getString(when (code) {
        ConfigImporter.SUB_ERROR_NETWORK -> R.string.tv_sub_failed_network
        ConfigImporter.SUB_ERROR_URL -> R.string.tv_sub_failed_url
        else -> R.string.tv_sub_failed_empty
    }))

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val row: ItemTvSubscriptionBinding) : RecyclerView.ViewHolder(row.root)

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) = subscriptions[position].first.hashCode().toLong()

        override fun getItemCount() = subscriptions.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTvSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (id, item) = subscriptions[position]
            val row = holder.row
            row.name.text = item.remarks
            row.state.setText(if (item.enabled) R.string.tv_sub_enabled else R.string.tv_sub_disabled)
            row.state.setTextColor(ContextCompat.getColorStateList(this@TvSubscriptionsActivity,
                if (item.enabled) R.color.tv_text_accent else R.color.tv_text_secondary))

            val count = MmkvManager.decodeServerList().count { MmkvManager.decodeServerConfig(it)?.subscriptionId == id }
            val failed = !refreshing.contains(id) && item.lastError.isNotEmpty()
            val status = when {
                refreshing.contains(id) -> getString(R.string.tv_sub_refreshing)
                failed -> failureText(item.lastError)
                System.currentTimeMillis() - item.lastUpdated < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.tv_sub_just_now)
                item.lastUpdated > 0 -> getString(R.string.tv_sub_last, DateUtils.getRelativeTimeSpanString(item.lastUpdated))
                else -> getString(R.string.tv_sub_never)
            }
            row.meta.text = listOf(TvFormat.urlHost(item.url), getString(R.string.tv_sub_count, count), status)
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
            row.meta.setTextColor(ContextCompat.getColorStateList(this@TvSubscriptionsActivity,
                if (failed) R.color.tv_text_error else R.color.tv_text_secondary))

            row.rowMain.setOnClickListener { showOptions(id, item) }
            row.btnRefresh.visibility = if (item.url.isBlank()) View.INVISIBLE else View.VISIBLE
            row.btnRefresh.setOnClickListener { refresh(id, item) }
        }
    }
}
