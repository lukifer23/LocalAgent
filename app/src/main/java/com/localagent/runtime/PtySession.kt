package com.localagent.runtime

import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.io.IOException

class PtySession private constructor(
    val master: ParcelFileDescriptor,
    val childPid: Int,
) : AutoCloseable {

    fun fd(): Int = master.fd

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        FileOutputStream(master.fileDescriptor).use { stream ->
            stream.write(bytes, offset, length)
            stream.flush()
        }
    }

    fun resize(rows: Int, cols: Int) {
        val rc = nativeResize(master.fd, rows, cols)
        if (rc != 0) {
            throw IOException("resize failed rc=$rc")
        }
    }

    override fun close() {
        nativeKill(childPid)
        master.close()
    }

    companion object {
        init {
            System.loadLibrary("localagent_native")
        }

        @JvmStatic
        external fun nativeSpawn(argv: Array<String>, env: Array<String>?, cwd: String?, rows: Int, cols: Int): LongArray?

        @JvmStatic
        external fun nativeResize(masterFd: Int, rows: Int, cols: Int): Int

        @JvmStatic
        external fun nativeKill(childPid: Int): Int

        fun spawn(argv: Array<String>, env: Array<String>?, cwd: String?, rows: Int, cols: Int): PtySession {
            val out =
                nativeSpawn(argv, env, cwd, rows, cols)
                    ?: throw IOException("nativeSpawn returned null")
            if (out.size != 2) {
                throw IOException("nativeSpawn invalid payload")
            }
            val masterFd = out[0].toInt()
            val pid = out[1].toInt()
            val parcel = ParcelFileDescriptor.adoptFd(masterFd)
            return PtySession(parcel, pid)
        }
    }
}
