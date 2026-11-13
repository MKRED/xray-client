package com.mkred.xrayclient.log

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.mkred.xrayclient.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Логирование в файл.
 *
 * Всё пишется в обычный logcat, а фоновый поток читает logcat своего процесса
 * и складывает в файл. Так в файл попадает всё сразу: наши сообщения,
 * лог ядра Xray (тег GoLog), системные сообщения и падения.
 *
 * Файлы: logs/app.log (текущий) и logs/app.old.log (предыдущий), до 2 МБ каждый.
 */
object AppLog {
    private const val PREFIX = "XC/"
    private const val MAX_BYTES = 2L * 1024 * 1024

    private lateinit var dir: File
    private val lock = Any()
    private var writer: FileWriter? = null
    private var written = 0L

    val current: File get() = File(dir, "app.log")

    /** Последнее падение — показывается на экране ошибки при следующем запуске. */
    val lastCrash: File get() = File(dir, "last_crash.txt")

    /** Записать ошибку для экрана ошибки (и в лог). */
    fun recordCrash(where: String, ex: Throwable) {
        val sw = StringWriter().also { ex.printStackTrace(PrintWriter(it)) }
        val text = "Xray Client ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n${stamp()} · $where\n\n$sw"
        runCatching { lastCrash.writeText(text) }
        appendRaw("!!!!! ПАДЕНИЕ ${stamp()} · $where\n$sw")
    }
    private val previous: File get() = File(dir, "app.old.log")

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        openWriter()
        installCrashHandler()
        appendRaw(
            "\n===== Запуск ${stamp()} | Xray Client ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL} =====",
        )
        startCollector()
    }

    fun d(tag: String, msg: String) { Log.d(PREFIX + tag, msg) }
    fun i(tag: String, msg: String) { Log.i(PREFIX + tag, msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(PREFIX + tag, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(PREFIX + tag, msg, t) }

    // ---- сборщик logcat ----

    private fun startCollector() = thread(name = "log-collector", isDaemon = true) {
        var first = true
        while (true) {
            try {
                // Первый запуск: весь буфер процесса с его старта. Перезапуск: только новые строки.
                val cmd = mutableListOf("logcat", "-v", "threadtime", "--pid", Process.myPid().toString())
                if (!first) cmd += listOf("-T", "1")
                first = false
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        append(line, flush = !reader.ready())
                    }
                }
                proc.destroy()
            } catch (t: Throwable) {
                appendRaw("log-collector: ${t.message}")
            }
            Thread.sleep(1000)
        }
    }

    // ---- падения ----

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            // Пишем напрямую: сборщик может не успеть до завершения процесса
            recordCrash("поток ${th.name}", ex)
            previousHandler?.uncaughtException(th, ex)
        }
    }

    // ---- запись в файл ----

    private fun openWriter() {
        writer = FileWriter(current, true)
        written = current.length()
    }

    private fun appendRaw(line: String) = append(line, flush = true)

    private fun append(line: String, flush: Boolean) = synchronized(lock) {
        try {
            if (written > MAX_BYTES) rotate()
            writer?.apply {
                write(line); write("\n")
                if (flush) flush()
            }
            written += line.length + 1
        } catch (_: Throwable) {
        }
    }

    private fun rotate() {
        writer?.close()
        previous.delete()
        current.renameTo(previous)
        openWriter()
    }

    fun clear() = synchronized(lock) {
        writer?.close()
        previous.delete()
        current.delete()
        openWriter()
        appendRaw("===== Лог очищен ${stamp()} =====")
    }

    /** Последние строки текущего лога — для просмотра в приложении. */
    fun tail(maxLines: Int = 400): List<String> = synchronized(lock) {
        writer?.flush()
        if (!current.exists()) return emptyList()
        val lines = current.readLines()
        lines.takeLast(maxLines)
    }

    /**
     * Готовый к отправке файл: предыдущий + текущий лог, без секретов.
     * Кладётся в cache/logs, откуда его можно отдать через FileProvider.
     */
    fun export(context: Context): File = synchronized(lock) {
        writer?.flush()
        val out = File(context.cacheDir, "logs").apply { mkdirs() }
            .resolve("xray-client-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        out.bufferedWriter().use { w ->
            listOf(previous, current).filter { it.exists() }.forEach { f ->
                f.forEachLine { w.write(Sanitizer.clean(it)); w.write("\n") }
            }
        }
        out
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}

/** Убирает из лога то, что не стоит пересылать: UUID, ссылки на серверы и подписки. */
object Sanitizer {
    private val uuid = Regex("""\b([0-9a-fA-F]{4})[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val proxyLink = Regex("""\b(vless|vmess|trojan|ss|hysteria2|hy2)://\S+""")
    private val httpUrl = Regex("""(https?://[^/\s?#]+)[/?#]\S*""")

    fun clean(s: String): String = s
        .replace(proxyLink) { "${it.groupValues[1]}://…" }
        .replace(uuid) { "${it.groupValues[1]}…-uuid" }
        .replace(httpUrl) { "${it.groupValues[1]}/…" }
}
