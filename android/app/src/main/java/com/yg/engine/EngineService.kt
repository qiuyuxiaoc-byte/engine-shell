package com.yg.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * 前台服务：解压运行时（首启一次），然后用 proot 起 `dsh web`。
 * 引擎跑在 rootfs 里，监听 127.0.0.1:PORT，带 token；页面通过 Bridge 拿到它。
 */
class EngineService : Service() {

    companion object {
        const val CHANNEL = "engine"
        const val ID = 4711
        const val PORT = 3080
        @Volatile var ready = false
        @Volatile var token: String? = null
        @Volatile var lastError: String? = null
        @Volatile var authUrl: String? = null   // 引擎打印的带 token 的地址
        private val lines = ArrayDeque<String>()
        @Volatile private var restarting = false

        fun tail(): String = synchronized(lines) { lines.joinToString("\n") }
        fun requestRestart() { restarting = true }
    }

    private var proc: Process? = null
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "引擎", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        startForeground(ID, note("引擎启动中…"))
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (proc == null && !restarting) boot()
        if (restarting && proc != null) { restarting = false; proc?.destroy(); proc = null; boot() }
        return START_STICKY
    }

    private fun boot() {
        val files = File(applicationInfo.dataDir, "files")
        if (!File(files, "runtime/rootfs").isDirectory) {
            Thread {
                try {
                    RootfsInstaller(this).install { msg -> note(msg) }
                    spawn(files)
                } catch (t: Throwable) { lastError = t.message; note("解压失败：${t.message}") }
            }.start()
        } else spawn(files)
    }

    private fun spawn(files: File) {
        val script = File(files, "engine/start-engine.sh")
        val pb = ProcessBuilder("/system/bin/sh", script.absolutePath, files.absolutePath,
            applicationInfo.nativeLibraryDir, PORT.toString())
        pb.redirectErrorStream(true)
        val p = pb.start(); proc = p; note("引擎启动中…")
        Thread { pump(p) }.start()
    }

    private fun pump(p: Process) {
        val tok = Pattern.compile("(?:token=|\\?t=)([A-Za-z0-9._~-]{8,})")
        p.inputStream.bufferedReader().useLines { seq ->
            seq.forEach { line ->
                synchronized(lines) { lines.addLast(line); if (lines.size > 400) lines.removeFirst() }
                tok.matcher(line).takeIf { it.find() }?.let { token = it.group(1); ready = true; note("引擎就绪") }
                Regex("https?://127\\.0\\.0\\.1:$PORT\\S*").find(line)?.let {
                    if (it.value.contains("token")) { authUrl = it.value; ready = true; note("引擎就绪") }
                }
            }
        }
        if (!stopping.get()) { ready = false; lastError = tail().takeLast(1500) }
    }

    private fun note(t: String) = Notification.Builder(this, CHANNEL)
        .setContentTitle("引擎").setContentText(t).setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setOngoing(true).build()
        .also { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, it) }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopping.set(true); proc?.destroy(); proc = null; ready = false; super.onDestroy() }
}
