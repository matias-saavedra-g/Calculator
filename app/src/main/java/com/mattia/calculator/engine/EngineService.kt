package com.mattia.calculator.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.CountDownLatch

/**
 * Hosts CPython + SymPy (via Chaquopy) in the separate `:engine` process
 * (see AndroidManifest.xml). All Python calls happen on ONE dedicated thread with a
 * 64 MB stack (SymPy recurses deeply; default thread stacks are ~1 MB).
 *
 * The UI process kills this whole process when a computation exceeds its time
 * budget. That is the only reliable way to stop CPU-bound Python code.
 */
class EngineService : Service() {

    private lateinit var messenger: Messenger
    private var looper: Looper? = null

    private val engine: PyObject by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        Python.getInstance().getModule("calc_engine")
    }

    override fun onCreate() {
        super.onCreate()
        val ready = CountDownLatch(1)
        var handler: Handler? = null
        Thread(null, {
            Looper.prepare()
            looper = Looper.myLooper()
            handler = Handler(Looper.myLooper()!!) { msg -> handle(msg); true }
            ready.countDown()
            Looper.loop()
        }, "cas-engine", 64L * 1024 * 1024).start()
        ready.await()
        messenger = Messenger(handler!!)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        looper?.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val data = Bundle()
        try {
            when (msg.what) {
                EngineProtocol.MSG_WARMUP -> {
                    engine.callAttr("warmup")
                    data.putString(EngineProtocol.KEY_JSON, "")
                }
                EngineProtocol.MSG_EVAL -> {
                    val req = msg.data
                    val json = engine.callAttr(
                        "evaluate_json",
                        req.getString(EngineProtocol.KEY_TEXT, ""),
                        req.getString(EngineProtocol.KEY_ANS, ""),
                        req.getBoolean(EngineProtocol.KEY_QUICK, false),
                    ).toString()
                    data.putString(EngineProtocol.KEY_JSON, json)
                }
            }
        } catch (t: Throwable) {
            data.putString(EngineProtocol.KEY_ERROR, t.message ?: t.javaClass.simpleName)
        }
        val reply = Message.obtain(null, msg.what, msg.arg1, Process.myPid())
        reply.data = data
        try {
            replyTo.send(reply)
        } catch (_: RemoteException) {
            // UI process went away; nothing to do.
        }
    }
}
