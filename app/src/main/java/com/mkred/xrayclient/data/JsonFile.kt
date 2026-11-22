package com.mkred.xrayclient.data

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/**
 * Чтение и запись файлов данных без риска остаться с обрезанным файлом.
 * AtomicFile пишет новую версию отдельно и подменяет ею старую только после
 * успешной записи и fsync. Если приложение упадёт посреди записи,
 * при следующем чтении вернётся прежняя целая версия.
 */
internal object JsonFile {

    /** Содержимое файла или null, если файла ещё нет. */
    fun read(file: File): String? = try {
        String(AtomicFile(file).readFully(), Charsets.UTF_8)
    } catch (_: FileNotFoundException) {
        null
    }

    fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
    }
}
