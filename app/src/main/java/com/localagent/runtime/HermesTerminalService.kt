package com.localagent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localagent.MainActivity
import com.localagent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

class HermesTerminalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null

    private val outputFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    private var session: PtySession? = null

    inner class LocalBinder : Binder() {
        fun handle(): TerminalSessionHandle = object : TerminalSessionHandle {
            override val output: SharedFlow<ByteArray> = outputFlow

            override fun send(bytes: ByteArray) {
                session?.write(bytes)
            }

            override fun resize(rows: Int, cols: Int) {
                session?.resize(rows, cols)
            }
        }
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val rows = intent?.getIntExtra(EXTRA_ROWS, 24) ?: 24
        val cols = intent?.getIntExtra(EXTRA_COLS, 80) ?: 80
        startForeground(NOTIFICATION_ID, buildNotification())
        startShell(rows, cols)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        createChannel()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_local_agent)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Terminal session active")
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "LocalAgent terminal", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(channel)
        }
    }

    private fun startShell(rows: Int, cols: Int) {
        readerJob?.cancel()
        session?.close()
        val home = HermesPaths.syntheticHome(this).absolutePath
        val env =
            buildEnv(home).toTypedArray()
        val argv = arrayOf("/system/bin/sh")
        session =
            try {
                PtySession.spawn(argv, env, home, rows, cols)
            } catch (e: Exception) {
                scope.launch { outputFlow.emit(("shell spawn failed: ${e.message}\r\n").toByteArray(Charsets.UTF_8)) }
                null
            }

        val localSession = session ?: return
        readerJob =
            scope.launch {
                val buffer = ByteArray(16384)
                FileInputStream(localSession.master.fileDescriptor).use { stream ->
                    while (isActive) {
                        val read = stream.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        outputFlow.emit(buffer.copyOf(read))
                    }
                }
            }
    }

    private fun buildEnv(home: String): List<String> {
        val base = mutableMapOf<String, String>()
        base["HOME"] = home
        base["TERM"] = "xterm-256color"
        base["TMPDIR"] = "$home/tmp"
        base["LOCAL_AGENT"] = "1"
        HermesBridgeEnv.build(this).forEach { (k, v) ->
            base[k] = v
        }
        val envMap = System.getenv()
        for (key in envMap.keys) {
            val value = envMap[key] ?: continue
            if (key !in base) {
                base[key] = value
            }
        }
        File("$home/tmp").mkdirs()
        return base.map { "${it.key}=${it.value}" }
    }

    private fun shutdown() {
        readerJob?.cancel()
        readerJob = null
        session?.close()
        session = null
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "localagent-terminal"
        private const val NOTIFICATION_ID = 42
        const val EXTRA_ROWS = "extra_rows"
        const val EXTRA_COLS = "extra_cols"
        const val ACTION_SHUTDOWN = "com.localagent.runtime.SHUTDOWN"

        fun start(context: Context, rows: Int, cols: Int) {
            val intent = Intent(context, HermesTerminalService::class.java).apply {
                putExtra(EXTRA_ROWS, rows)
                putExtra(EXTRA_COLS, cols)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun shutdown(context: Context) {
            val intent = Intent(context, HermesTerminalService::class.java).apply {
                action = ACTION_SHUTDOWN
            }
            context.startService(intent)
        }
    }
}

interface TerminalSessionHandle {
    val output: SharedFlow<ByteArray>
    fun send(bytes: ByteArray)
    fun resize(rows: Int, cols: Int)
}
