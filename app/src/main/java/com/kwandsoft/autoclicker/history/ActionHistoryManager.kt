package com.kwandsoft.autoclicker.history

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class ActionRecord(
    val id: Long,
    val timestamp: Long,
    val code: Int,
    val title: String,
    val actionDesc: String,
    val imagePath: String?
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA).format(Date(timestamp))

    val shortTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(timestamp))
}

object ActionHistoryManager {
    private const val TAG = "ActionHistoryManager"
    private const val RETENTION_MS = 10 * 60 * 1000L // 10분 (항상 보관)

    private val idCounter = AtomicLong(1)
    private val records = Collections.synchronizedList(mutableListOf<ActionRecord>())
    private val scope = CoroutineScope(Dispatchers.IO)

    fun recordAction(
        context: Context,
        code: Int,
        title: String,
        actionDesc: String,
        evidenceBitmap: Bitmap?
    ) {
        val now = System.currentTimeMillis()
        val id = idCounter.getAndIncrement()

        // 비트맵 복사본을 만들어 IO 쓰레드에서 압축 저장
        val bitmapCopy = try {
            evidenceBitmap?.let { if (!it.isRecycled) it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) else null }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy evidence bitmap", e)
            null
        }

        scope.launch {
            var imagePath: String? = null
            try {
                val dir = File(context.filesDir, "action_history")
                if (!dir.exists()) dir.mkdirs()

                if (bitmapCopy != null && !bitmapCopy.isRecycled) {
                    val file = File(dir, "act_${now}_${id}.jpg")
                    FileOutputStream(file).use { out ->
                        bitmapCopy.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    }
                    imagePath = file.absolutePath
                    bitmapCopy.recycle()
                }

                val record = ActionRecord(
                    id = id,
                    timestamp = now,
                    code = code,
                    title = title,
                    actionDesc = actionDesc,
                    imagePath = imagePath
                )
                records.add(record)
                pruneOldRecords(dir, now)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving action record", e)
            }
        }
    }

    private fun pruneOldRecords(dir: File, now: Long) {
        val cutoff = now - RETENTION_MS
        synchronized(records) {
            val it = records.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (r.timestamp < cutoff) {
                    r.imagePath?.let { path ->
                        try {
                            val f = File(path)
                            if (f.exists()) f.delete()
                        } catch (_: Exception) {}
                    }
                    it.remove()
                }
            }
        }

        // 고아 파일 주기적 정리
        try {
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun getRecordsNewestFirst(): List<ActionRecord> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        synchronized(records) {
            return records.filter { it.timestamp >= cutoff }.reversed()
        }
    }

    fun getTotalCount(): Int {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        synchronized(records) {
            return records.count { it.timestamp >= cutoff }
        }
    }

    fun clearAll(context: Context) {
        synchronized(records) {
            records.clear()
        }
        scope.launch {
            try {
                val dir = File(context.filesDir, "action_history")
                dir.listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }
}
