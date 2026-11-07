package com.v2ray.ang.ui.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.V2rayConfigUtil
import java.util.concurrent.Executors

/**
 * The one source of connection state for TV screens. Every state comes from a service broadcast;
 * CONNECTING and DISCONNECTING only mark a request in flight, and watchdogs end them if the
 * service never answers, so the UI can't sit on a state nobody confirmed.
 */
object TvConnection {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
    sealed class Test {
        object Running : Test()
        /** [delayMs] is set when the result carries a usable latency. */
        data class Done(val message: String, val ok: Boolean, val delayMs: Long? = null) : Test()
    }

    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val STOP_TIMEOUT_MS = 8_000L
    private const val PROBE_TIMEOUT_MS = 1_500L
    private const val RESTART_DELAY_MS = 500L
    private const val TEST_TIMEOUT_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private lateinit var app: Context

    private val _state = MutableLiveData(State.DISCONNECTED)
    private val _test = MutableLiveData<Test?>()
    private val _results = MutableLiveData<String>()
    val state: LiveData<State> = _state
    val test: LiveData<Test?> = _test
    /** Emits the guid whose stored latency just changed. */
    val results: LiveData<String> = _results

    @StringRes
    var errorRes: Int = 0
        private set
    private var testingGuid: String? = null

    val isBusy get() = state.value == State.CONNECTING || state.value == State.DISCONNECTING
    val isConnected get() = state.value == State.CONNECTED

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        app.registerReceiver(receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY))
    }

    /** Asks the service for the truth; a live service answers, a dead one stays silent. */
    fun probe() {
        MessageUtil.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
        if (isConnected) {
            handler.removeCallbacks(probeExpired)
            handler.postDelayed(probeExpired, PROBE_TIMEOUT_MS)
        }
    }

    fun connect() {
        if (isBusy || isConnected) return
        val guid = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).orEmpty()
        if (guid.isEmpty()) return
        set(State.CONNECTING)
        io.execute {
            val valid = V2rayConfigUtil.getV2rayConfig(app, guid).status
            handler.post {
                if (state.value != State.CONNECTING) return@post
                if (valid) startService() else fail(R.string.tv_detail_error_invalid)
            }
        }
    }

    fun disconnect() {
        if (isBusy || !isConnected) return
        set(State.DISCONNECTING)
        Utils.stopVService(app)
        handler.postDelayed(stopExpired, STOP_TIMEOUT_MS)
    }

    /** Applies a newly selected server to a running connection. */
    fun restart() {
        if (!isConnected && state.value != State.CONNECTING) return
        handler.removeCallbacks(restartNow)
        set(State.CONNECTING)
        Utils.stopVService(app)
        handler.postDelayed(restartNow, RESTART_DELAY_MS)
    }

    fun permissionDenied() = fail(R.string.tv_detail_error_permission)

    /** Through the running tunnel when connected, otherwise a direct probe of the selected server. */
    fun testSelected() {
        if (test.value == Test.Running) return
        if (isConnected) {
            _test.value = Test.Running
            handler.postDelayed(testExpired, TEST_TIMEOUT_MS)
            MessageUtil.sendMsg2Service(app, AppConfig.MSG_MEASURE_DELAY, "")
        } else {
            val guid = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER) ?: return
            _test.value = Test.Running
            testingGuid = guid
            handler.postDelayed(testExpired, TEST_TIMEOUT_MS)
            measure(guid)
        }
    }

    fun clearTest() {
        handler.removeCallbacks(testExpired)
        testingGuid = null
        _test.value = null
    }

    /** Real-delay test of one stored server; the result arrives through [results]. */
    fun measure(guid: String) {
        io.execute {
            val config = V2rayConfigUtil.getV2rayConfig(app, guid)
            if (config.status) {
                MessageUtil.sendMsg2TestService(app, AppConfig.MSG_MEASURE_CONFIG, Pair(guid, config.content))
            } else handler.post {
                MmkvManager.encodeServerTestDelayMillis(guid, -1L)
                onResult(guid, -1L)
            }
        }
    }

    private fun startService() {
        V2RayServiceManager.startV2Ray(app)
        handler.removeCallbacks(startExpired)
        handler.postDelayed(startExpired, CONNECT_TIMEOUT_MS)
    }

    private fun fail(@StringRes message: Int) {
        errorRes = message
        set(State.ERROR)
    }

    private fun set(next: State) {
        handler.removeCallbacks(startExpired)
        handler.removeCallbacks(stopExpired)
        handler.removeCallbacks(probeExpired)
        if (next != _state.value) clearTest() // A result describes the connection that was tested.
        _state.value = next
    }

    private val restartNow = Runnable { startService() }

    private val startExpired = Runnable {
        fail(R.string.tv_detail_error_timeout)
        probe()
    }

    private val stopExpired = Runnable {
        set(State.DISCONNECTED)
        probe()
    }

    private val probeExpired = Runnable { set(State.DISCONNECTED) }

    private val testExpired = Runnable {
        testingGuid = null
        _test.value = Test.Done(app.getString(R.string.tv_test_failed), false)
    }

    private fun onResult(guid: String, delay: Long) {
        _results.value = guid
        if (guid == testingGuid) {
            handler.removeCallbacks(testExpired)
            testingGuid = null
            _test.value = if (delay > 0) Test.Done(app.getString(R.string.tv_test_ok, delay), true, delay)
            else Test.Done(app.getString(R.string.tv_test_failed), false)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    handler.removeCallbacks(restartNow)
                    set(State.CONNECTED)
                }
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    handler.removeCallbacks(restartNow)
                    set(State.CONNECTED)
                    // The core starts even when the server is dead, so "connected" alone proves nothing.
                    testSelected()
                }
                AppConfig.MSG_STATE_NOT_RUNNING -> when (state.value) {
                    State.CONNECTED, State.DISCONNECTING -> set(State.DISCONNECTED)
                    else -> Unit
                }
                AppConfig.MSG_STATE_START_FAILURE -> fail(R.string.tv_detail_error_start)
                // A restart stops the service on its way to starting it again.
                AppConfig.MSG_STATE_STOP_SUCCESS -> if (state.value != State.CONNECTING) set(State.DISCONNECTED)
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    handler.removeCallbacks(testExpired)
                    val message = intent.getStringExtra("content").orEmpty()
                    val errorPrefix = app.getString(R.string.connection_test_error, "").trim()
                    val ok = !message.startsWith(errorPrefix)
                    // The core reports "[first, warm] ms"; the warm figure is what browsing feels like.
                    val warm = if (ok) Regex("\\d+").findAll(message).lastOrNull()?.value?.toLongOrNull() else null
                    val guid = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).orEmpty()
                    if (guid.isNotEmpty()) {
                        MmkvManager.encodeServerTestDelayMillis(guid, warm ?: -1L)
                        _results.value = guid
                    }
                    _test.value = if (warm != null) Test.Done(app.getString(R.string.tv_test_ok, warm), true, warm)
                    else Test.Done(message, ok)
                }
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    @Suppress("UNCHECKED_CAST", "DEPRECATION")
                    val result = intent.getSerializableExtra("content") as Pair<String, Long>
                    MmkvManager.encodeServerTestDelayMillis(result.first, result.second)
                    onResult(result.first, result.second)
                }
            }
        }
    }
}
