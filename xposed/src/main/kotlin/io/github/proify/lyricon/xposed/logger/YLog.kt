/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.xposed.logger

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 日志工具类
 * 优先使用 LSPosed 框架日志，回退到 Android Logcat
 *
 * 除输出外，还会把最近 [MAX_BUFFER_LINES] 条日志保留在内存环形缓冲中，
 * 供 App 端通过跨进程桥接拉取（「导出日志」），release 版同样可用。
 */
object YLog {
    const val TAG = "Lyricon"

    /** 内存日志缓冲保留的最大行数 */
    private const val MAX_BUFFER_LINES = 3000

    private var xposedInterface: XposedInterface? = null

    /** 内存日志缓冲（线程安全由 [bufferLock] 保证） */
    private val bufferLock = Any()
    private val buffer = ArrayDeque<String>(MAX_BUFFER_LINES)

    private val dateLock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 初始化日志系统
     * @param ctx XposedInterface 实例（通常是 XposedModule）
     */
    fun init(ctx: XposedInterface) {
        xposedInterface = ctx
    }

    fun info(tag: String, msg: String) {
        log(Log.INFO, tag, msg)
    }

    fun debug(tag: String, msg: String) {
        log(Log.DEBUG, tag, msg)
    }

    fun error(tag: String, msg: String) {
        log(Log.ERROR, tag, msg)
    }

    fun verbose(tag: String, msg: String) {
        log(Log.VERBOSE, tag, msg)
    }

    fun warning(tag: String, msg: String) {
        log(Log.WARN, tag, msg)
    }

    fun error(tag: String, msg: String?, e: Throwable?) {
        append('E', tag, buildMessage(tag, msg) + (e?.let { " | ${it.stackTraceToString()}" } ?: ""))

        val xi = xposedInterface
        if (xi != null) {
            xi.log(Log.ERROR, TAG, buildMessage(tag, msg), e)
        } else {
            Log.e(TAG, buildMessage(tag, msg), e)
        }
    }

    private fun log(priority: Int, tag: String, msg: String) {
        val priorityChar = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> '?'
        }
        append(priorityChar, tag, msg)

        val xi = xposedInterface
        if (xi != null) {
            xi.log(priority, TAG, buildMessage(tag, msg))
        } else {
            when (priority) {
                Log.VERBOSE -> Log.v(TAG, buildMessage(tag, msg))
                Log.DEBUG -> Log.d(TAG, buildMessage(tag, msg))
                Log.INFO -> Log.i(TAG, buildMessage(tag, msg))
                Log.WARN -> Log.w(TAG, buildMessage(tag, msg))
                Log.ERROR -> Log.e(TAG, buildMessage(tag, msg))
            }
        }
    }

    private fun buildMessage(tag: String, msg: String?): String {
        return "[$tag] ${msg ?: ""}"
    }

    // --- 内存缓冲 ---

    private fun append(priorityChar: Char, tag: String, text: String) {
        val time = synchronized(dateLock) { timeFormat.format(Date()) }
        val line = "$time $priorityChar $tag: $text"

        synchronized(bufferLock) {
            while (buffer.size >= MAX_BUFFER_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
        }
    }

    /**
     * 导出内存日志缓冲。
     *
     * @param header 追加在正文前的额外信息（如运行时诊断快照）
     */
    fun dumpBuffer(header: String? = null): String = buildString {
        val snapshot = synchronized(bufferLock) { ArrayList(buffer) }

        appendLine("===== Lyricon SystemUI 日志 =====")
        appendLine("缓冲行数: ${snapshot.size} / $MAX_BUFFER_LINES")
        appendLine("导出时间: ${synchronized(dateLock) { timeFormat.format(Date()) }}")
        if (!header.isNullOrBlank()) {
            appendLine()
            appendLine(header)
        }
        appendLine()
        snapshot.forEach { appendLine(it) }
        appendLine("===== 结束 =====")
    }

    /** 清空内存日志缓冲 */
    fun clearBuffer() {
        synchronized(bufferLock) { buffer.clear() }
    }
}
