package ie.unidatum.hospice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The unidatum node as a foreground service. Android runs a native executable an
 * app owns as long as it sits in the app's native-library directory, so the engine
 * ships as lib/arm64-v8a/libunidatum.so and is exec'd from there; the repository
 * lives in filesDir/node. DuckDB is its musl CLI, run through musl's loader
 * (libmusl.so) by the libduckwrap.so script, with libstdcpp6.so and libgccs1.so
 * beside it.
 *
 * The service outlives the screen because a nurse charts with the phone in a
 * pocket between rooms, and because the node keeps serving its members to the
 * branch while it does.
 */
class NodeService : Service() {
    companion object {
        const val TAG = "unidatum"
        const val CHANNEL = "node"
        const val UI_PORT = 7482
        const val SYNC_PORT = 47804
        const val DHT_PORT = 47805
        val process = AtomicReference<Process?>(null)
        val log = ArrayDeque<String>()
        fun logLine(s: String) { synchronized(log) { log.addLast(s); while (log.size > 300) log.removeFirst() } }
        fun logText(): String = synchronized(log) { log.joinToString("\n") }
        fun running() = process.get()?.isAlive == true
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, NodeService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, NodeService::class.java)) }
        fun nodeDir(ctx: Context) = File(ctx.filesDir, "node")
        fun bin(ctx: Context, name: String) = File(ctx.applicationInfo.nativeLibraryDir, name)
        fun nodeName(): String = "rn-" + android.os.Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running()) Thread { run() }.start()
        if (!syncLoop) { syncLoop = true; Thread { poll() }.start() }
        return START_STICKY
    }

    @Volatile private var syncLoop = false

    /** Every 10 s: refresh what the app knows from its own node. Reads only; a visit is written when the nurse acts. */
    private fun poll() {
        val n = Nurse.get(this)
        while (syncLoop) {
            try {
                if (n.joined) {
                    if (!n.following) n.ensureFollowing(1)
                    n.refreshDay()
                }
            } catch (e: Exception) { NodeService.logLine("poll: ${e.message}") }
            Thread.sleep(10000)
        }
    }

    private fun run() {
        try {
            val dir = nodeDir(this); dir.mkdirs()
            val engine = bin(this, "libunidatum.so")
            val library = Nurse.get(this).library
            if (!File(dir, ".p2pfs").exists()) {
                logLine("init: library $library as ${nodeName()}")
                exec(dir, engine.path, "init", "--library", library, "--node-name", nodeName())
            }
            val pb = ProcessBuilder(engine.path, "ui", "--port", "$SYNC_PORT", "--dht-port", "$DHT_PORT", "--ui-port", "$UI_PORT",
                "--bind", "0.0.0.0", "--sql", "--no-mdns", "--sync-every", "5", "--seed-open")
                .directory(dir).redirectErrorStream(true)
            pb.environment()["P2PFS_DUCKDB"] = bin(this, "libduckwrap.so").path
            pb.environment()["HOME"] = dir.path
            val p = pb.start(); process.set(p)
            logLine("node: started")
            p.inputStream.bufferedReader().forEachLine { logLine(it); Log.i(TAG, it) }
            logLine("node: exited ${p.waitFor()}")
        } catch (e: Exception) { logLine("node: $e"); Log.e(TAG, "node", e) }
        process.set(null)
        stopSelf()
    }

    private fun exec(dir: File, vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        out.lines().filter { it.isNotBlank() }.forEach { logLine(it) }
        return out
    }

    override fun onDestroy() {
        syncLoop = false
        process.get()?.destroy(); process.set(null)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "unidatum node", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle("hospice node")
            .setContentText("the day's visits, on this phone").setSmallIcon(R.drawable.ic_node).setContentIntent(pi).setOngoing(true).build()
    }
}
