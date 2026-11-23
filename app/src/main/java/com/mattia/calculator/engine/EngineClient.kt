package com.mattia.calculator.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.mattia.calculator.model.EngineReply
import com.mattia.calculator.model.EngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

enum class EngineStatus { Starting, Ready, Restarting }

/**
 * UI-process side of the engine. Binds to [EngineService] (process `:engine`),
 * sends one request at a time and enforces a time budget per request. When the budget
 * is exceeded, the engine process is killed and started again, like `worker.py` in
 * the Flow Launcher plugin.
 *
 * Must be used from the main thread. [scope] should be a long-lived scope (viewModelScope).
 */
class EngineClient(context: Context, private val scope: CoroutineScope) {

    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow(EngineStatus.Starting)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val service = MutableStateFlow<Messenger?>(null)
    private var servicePid = 0
    private var bound = false
    private var nextId = 1
    private val pending = HashMap<Int, CompletableDeferred<Bundle>>()
    private val gate = Mutex()

    private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.arg2 > 0) servicePid = msg.arg2
        pending.remove(msg.arg1)?.complete(Bundle(msg.data))
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service.value = Messenger(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Process died (killed by us or crashed). Fail whatever was waiting.
            service.value = null
            _status.value = EngineStatus.Restarting
            failPending("engine process stopped")
        }

        override fun onBindingDied(name: ComponentName) {
            rebind()
        }
    }

    fun start() {
        bind()
        scope.launch { warmup() }
    }

    fun stop() {
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        service.value = null
        failPending("engine stopped")
    }

    /**
     * Evaluate [text]. If the caller is cancelled before the request is sent, it is
     * dropped. Once sent, it keeps the engine busy until it answers or times out.
     */
    suspend fun evaluate(text: String, ans: String, quick: Boolean, timeoutMs: Long): EngineResult {
        var sent = false
        val job = scope.async {
            gate.withLock {
                sent = true
                request(EngineProtocol.MSG_EVAL, timeoutMs) {
                    putString(EngineProtocol.KEY_TEXT, text)
                    putString(EngineProtocol.KEY_ANS, ans)
                    putBoolean(EngineProtocol.KEY_QUICK, quick)
                }
            }
        }
        try {
            return job.await()
        } catch (e: CancellationException) {
            if (!sent) job.cancel()
            throw e
        }
    }

    private suspend fun warmup() {
        gate.withLock {
            when (val r = request(EngineProtocol.MSG_WARMUP, WARMUP_TIMEOUT_MS) {}) {
                is EngineResult.Done -> _status.value = EngineStatus.Ready
                else -> Log.w(TAG, "warmup failed: $r")
            }
        }
    }

    /** Runs with [gate] held. */
    private suspend fun request(what: Int, timeoutMs: Long, fill: Bundle.() -> Unit): EngineResult {
        if (!bound) bind()
        val messenger = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { service.filterNotNull().first() }
            ?: return EngineResult.Failed("engine did not start")
        val id = nextId++
        val reply = CompletableDeferred<Bundle>()
        pending[id] = reply
        val msg = Message.obtain(null, what, id, 0).apply {
            data = Bundle().apply(fill)
            replyTo = replyMessenger
        }
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            pending.remove(id)
            return EngineResult.Failed("engine unreachable")
        }
        val data = try {
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } catch (e: IllegalStateException) {
            return EngineResult.Failed(e.message ?: "engine stopped")
        }
        if (data == null) {
            pending.remove(id)
            if (what == EngineProtocol.MSG_EVAL) restartEngine()   // never loop on a slow warmup
            return EngineResult.Timeout
        }
        data.getString(EngineProtocol.KEY_ERROR)?.let { return EngineResult.Failed(it) }
        val payload = data.getString(EngineProtocol.KEY_JSON).orEmpty()
        if (what == EngineProtocol.MSG_WARMUP) return EngineResult.Done(EngineReply(ok = true))
        return try {
            EngineResult.Done(json.decodeFromString(EngineReply.serializer(), payload))
        } catch (e: Exception) {
            EngineResult.Failed("bad engine reply")
        }
    }

    private fun restartEngine() {
        Log.w(TAG, "engine over time budget, killing pid $servicePid")
        _status.value = EngineStatus.Restarting
        val pid = servicePid
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        servicePid = 0
        rebind()
        scope.launch { warmup() }
    }

    private fun bind() {
        bound = app.bindService(Intent(app, EngineService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun rebind() {
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        service.value = null
        failPending("engine restarting")
        bind()
    }

    private fun failPending(reason: String) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.completeExceptionally(IllegalStateException(reason)) }
    }

    companion object {
        private const val TAG = "EngineClient"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val WARMUP_TIMEOUT_MS = 90_000L
    }
}
