package com.v2ray.ang.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.tbruyelle.rxpermissions.RxPermissions
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.drakeet.support.toast.ToastCompat
import java.io.File
import java.io.FileOutputStream

/** Config and subscription import, shared by the phone and TV screens. Create it as an activity field. */
class ConfigImporter(private val activity: AppCompatActivity, private val viewModel: () -> MainViewModel) {
    companion object {
        const val SUB_ERROR_NETWORK = "network"
        const val SUB_ERROR_URL = "url"
        const val SUB_ERROR_EMPTY = "empty"

        fun prepareFirstRun(activity: AppCompatActivity) {
            val extFolder = Utils.userAssetPath(activity)
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val geo = arrayOf("geosite.dat", "geoip.dat")
                    activity.assets.list("")
                        ?.filter { geo.contains(it) }
                        ?.filter { !File(extFolder, it).exists() }
                        ?.forEach {
                            val target = File(extFolder, it)
                            activity.assets.open(it).use { input ->
                                FileOutputStream(target).use { output -> input.copyTo(output) }
                            }
                            Log.i(ANG_PACKAGE, "Copied from apk assets folder to ${target.absolutePath}")
                        }
                } catch (e: Exception) {
                    Log.e(ANG_PACKAGE, "asset copy failed", e)
                }
            }
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val result = AngConfigManager.migrateLegacyConfig(activity)
                if (result != null) withContext(Dispatchers.Main) {
                    activity.toast(if (result) R.string.migration_success else R.string.migration_fail)
                }
            }
        }
    }

    private val subStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SUB, MMKV.MULTI_PROCESS_MODE) }

    private val scanQRCodeForConfig = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == AppCompatActivity.RESULT_OK) importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
    }

    private val scanQRCodeForUrlToCustomConfig = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == AppCompatActivity.RESULT_OK) importConfigCustomUrl(it.data?.getStringExtra("SCAN_RESULT"))
    }

    private val chooseFileForCustomConfig = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == AppCompatActivity.RESULT_OK && uri != null) readContentFromUri(uri)
    }

    fun importManually(createConfigType: Int) {
        activity.startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", viewModel().subscriptionId)
                .setClass(activity, ServerActivity::class.java)
        )
    }

    fun importQRcode(forConfig: Boolean): Boolean {
        RxPermissions(activity)
            .request(Manifest.permission.CAMERA)
            .subscribe {
                if (it) {
                    val intent = Intent(activity, ScannerActivity::class.java)
                    if (forConfig) scanQRCodeForConfig.launch(intent) else scanQRCodeForUrlToCustomConfig.launch(intent)
                } else {
                    activity.toast(R.string.toast_permission_denied)
                }
            }
        return true
    }

    fun importClipboard(): Boolean {
        try {
            importBatchConfig(Utils.getClipboard(activity))
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun importBatch(server: String?, subid: String): Int {
        val append = subid.isEmpty()
        val target = subid.ifEmpty { viewModel().subscriptionId }
        val count = AngConfigManager.importBatchConfig(server, target, append)
        return if (count > 0 || server == null) count else AngConfigManager.importBatchConfig(Utils.decode(server), target, append)
    }

    fun importBatchConfig(server: String?, subid: String = "") {
        if (importBatch(server, subid) > 0) {
            activity.toast(R.string.toast_success)
            viewModel().reloadServerList()
        } else {
            activity.toast(R.string.toast_failure)
        }
    }

    fun importConfigCustomClipboard(): Boolean {
        try {
            val configText = Utils.getClipboard(activity)
            if (TextUtils.isEmpty(configText)) {
                activity.toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigCustomUrlClipboard(): Boolean {
        try {
            val url = Utils.getClipboard(activity)
            if (TextUtils.isEmpty(url)) {
                activity.toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                activity.toast(R.string.toast_invalid_url)
                return false
            }
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val configText = try {
                    Utils.getUrlContentWithCustomUserAgent(url)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                launch(Dispatchers.Main) { importCustomizeConfig(configText) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /** Refreshes every enabled subscription, reporting through toasts. */
    fun importConfigViaSub(): Boolean {
        try {
            activity.toast(R.string.title_sub_update)
            MmkvManager.decodeSubscriptions().forEach { (id, item) ->
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(item.remarks) || TextUtils.isEmpty(item.url) || !item.enabled) {
                    return@forEach
                }
                refreshSubscription(id, item) { error ->
                    if (error == null) {
                        activity.toast(R.string.toast_success)
                    } else {
                        activity.toast("\"${item.remarks}\" ${activity.getString(R.string.toast_failure)}")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /** Downloads one subscription and records the outcome on it. [onDone] gets a SUB_ERROR_* code, or null on success. */
    fun refreshSubscription(id: String, item: SubscriptionItem, onDone: (String?) -> Unit) {
        val url = Utils.idnToASCII(item.url)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val configText = if (Utils.isValidUrl(url)) try {
                Utils.getUrlContentWithCustomUserAgent(url)
            } catch (e: Exception) {
                Log.d(ANG_PACKAGE, "subscription refresh failed", e)
                null
            } else null
            withContext(Dispatchers.Main) {
                val error = when {
                    !Utils.isValidUrl(url) -> SUB_ERROR_URL
                    configText == null -> SUB_ERROR_NETWORK
                    // importBatchConfig drops the subscription's servers before parsing, so a captive
                    // portal page or an error body must never reach it.
                    !containsShareLinks(configText) -> SUB_ERROR_EMPTY
                    else -> try {
                        if (importBatch(configText, id) > 0) null else SUB_ERROR_EMPTY
                    } catch (e: Exception) {
                        SUB_ERROR_EMPTY
                    }
                }
                item.lastError = error.orEmpty()
                if (error == null) item.lastUpdated = System.currentTimeMillis()
                subStorage?.encode(id, Gson().toJson(item))
                viewModel().reloadServerList()
                onDone(error)
            }
        }
    }

    private fun containsShareLinks(text: String): Boolean {
        val schemes = EConfigType.values().map { it.protocolScheme }.filter { it.isNotEmpty() }
        return (text + "\n" + Utils.decode(text)).lines().any { line -> schemes.any { line.trim().startsWith(it) } }
    }

    fun showQr(bitmap: Bitmap?) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(activity as Context))
        ivBinding.ivQcode.setImageBitmap(bitmap)
        AlertDialog.Builder(activity).setView(ivBinding.root).show()
    }

    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, activity.getString(R.string.title_file_chooser)))
        } catch (ex: ActivityNotFoundException) {
            activity.toast(R.string.toast_require_file_manager)
        }
    }

    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        RxPermissions(activity)
            .request(permission)
            .subscribe {
                if (it) {
                    try {
                        activity.contentResolver.openInputStream(uri).use { input ->
                            importCustomizeConfig(input?.bufferedReader()?.readText())
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    activity.toast(R.string.toast_permission_denied)
                }
            }
    }

    fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                activity.toast(R.string.toast_none_data)
                return
            }
            viewModel().appendCustomConfigServer(server)
            viewModel().reloadServerList()
            activity.toast(R.string.toast_success)
        } catch (e: Exception) {
            ToastCompat.makeText(activity, "${activity.getString(R.string.toast_malformed_josn)} ${e.cause?.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
}
