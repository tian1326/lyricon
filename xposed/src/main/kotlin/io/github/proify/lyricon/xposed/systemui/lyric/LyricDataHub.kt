/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.util.Log
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.systemui.lyric.processor.LyricDataProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 歌词数据调度中枢
 * 管理歌词生命周期：获取原始数据 -> 后台加工 -> 第一次分发 -> 后台增强 -> 最终分发。
 */
object LyricDataHub : ActivePlayerListener {
    private const val TAG = "LyricDataHub"

    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 状态版本号，用于在异步恢复后校验数据是否已过时 */
    private val versionCounter = AtomicInteger(0)

    /** 缓存当前的原始歌曲，用于配置变更时重走流程 */
    private var cachedRawSong: Song? = null

    /** 缓存当前的纯文本歌词（无 Lrc 时），同样用于断线重连后补发 */
    private var cachedRawText: String? = null

    /**
     * [cachedRawSong] 来自哪个播放器包名
     *
     * 切换到别的播放器后，缓存里的歌属于上一个播放器，重发会串台，
     * 因此补发前必须用这个字段校验。
     */
    private var cachedSourcePackage: String? = null

    /** 缓存最近的播放状态与进度，用于断线重连后补发一次完整快照 */
    private var cachedIsPlaying: Boolean = false
    private var cachedPosition: Long = 0

    /** 当前执行中的流水线任务 */
    private var activePipelineJob: Job? = null

    /** 当前活动的提供者信息 */
    private var providerInfo: ProviderInfo? = null

    fun addListener(listener: ActivePlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ActivePlayerListener) {
        listeners.remove(listener)
    }

    /**
     * 启动后台加工流水线。
     * 所有歌词加工都在后台协程执行，避免阻塞播放器回调线程。
     * @param rawSong 待加工的原始歌曲
     */
    private fun runProcessingPipeline(rawSong: Song?) {
        val song = rawSong?.deepCopy()
        val currentVersion = versionCounter.incrementAndGet()
        activePipelineJob?.cancel()

        activePipelineJob = scope.launch {
            try {
                if (song == null) {
                    if (isCurrentVersion(currentVersion)) dispatchSong(null)
                    return@launch
                }

                val style = LyricPrefs.getLyricStyle()

                // 1. 后台前置加工：处理繁简、屏蔽词等基础显示数据
                val preProcessed = LyricDataProcessor.executePreProcessing(song)
                if (!isCurrentVersion(currentVersion)) return@launch

                // 第一次分发：基础处理完成后尽快刷新 UI
                dispatchSong(LyricDataProcessor.executeDisplayProcessing(preProcessed, style))

                // 2. 后台后置流水线：处理 AI 翻译等耗时扩展
                val finalSong =
                    LyricDataProcessor.executePostProcessingPipeline(preProcessed, style)

                if (isCurrentVersion(currentVersion)) {
                    dispatchSong(LyricDataProcessor.executeDisplayProcessing(finalSong, style))
                } else {
                    logOutdatedPipeline(currentVersion, finalSong)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Pipeline $currentVersion cancelled. $e")
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline $currentVersion error", e)
            }
        }
    }

    private fun isCurrentVersion(version: Int): Boolean = version == versionCounter.get()

    private fun logOutdatedPipeline(version: Int, song: Song) {
        Log.d(
            TAG,
            "Pipeline requestVersion:$version, nowVersion:${versionCounter.get()} skipped. ${song.name}"
        )
    }

    /**
     * 重走加工流程
     * 当配置（繁简、AI 开关、翻译模式）变更时调用，无需切歌即可应用新设置。
     */
    fun reprocessCurrentSong() {
        runProcessingPipeline(cachedRawSong)
    }

    /**
     * 主动请求"当前歌曲"重新推送到各个监听器
     *
     * 用于播放器 provider 断开重连的场景：视图内容在断开时已被清空，而上游
     * （provider 进程）在重连后往往只继续推进度、不再重发 onSongChanged，
     * 于是歌词一直空白。这里用本进程缓存的原始数据补发一次完整快照。
     *
     * 注意：补发会清掉派发去重指纹（[lastDispatchSongId]），否则同一首歌会被
     * 判为重复而丢弃——这正是"重连后一次 onSongChanged 都收不到"的直接原因。
     *
     * @param expectedPackage 期望的播放器包名；若缓存来自其它播放器则放弃补发，避免串台
     */
    fun requestCurrentSong(expectedPackage: String? = null) {
        val source = cachedSourcePackage
        if (!expectedPackage.isNullOrBlank() && !source.isNullOrBlank() && source != expectedPackage) {
            Log.d(
                TAG,
                "requestCurrentSong: cache belongs to $source but expected $expectedPackage, skip"
            )
            return
        }

        val song = cachedRawSong
        val text = cachedRawText
        if (song == null && text.isNullOrBlank()) {
            Log.d(TAG, "requestCurrentSong: nothing cached, skip")
            return
        }

        Log.d(TAG, "requestCurrentSong: resend cached state (hasSong=${song != null})")
        lastDispatchSongId = NO_DISPATCHED_SONG

        if (song != null) {
            runProcessingPipeline(song)
        } else {
            listeners.forEach { it.onReceiveText(text) }
        }

        // 播放器重连后只会继续推进度，播放状态可能再也不发，这里一并补发
        if (cachedIsPlaying) {
            listeners.forEach { it.onPlaybackStateChanged(true) }
        }
        if (cachedPosition > 0) {
            listeners.forEach { it.onPositionChanged(cachedPosition) }
        }
    }

    // --- ActivePlayerListener 触发点 ---

    override fun onSongChanged(song: Song?) {
        this.cachedRawSong = song?.deepCopy()
        this.cachedSourcePackage = providerInfo?.playerPackageName
        runProcessingPipeline(song)
    }

    /** 尚未派发过任何歌曲时的哨兵值（null 歌曲的指纹是 0，不能用 0 表示"无"） */
    private val NO_DISPATCHED_SONG = -1

    private var lastDispatchSongId = NO_DISPATCHED_SONG
    private fun dispatchSong(song: Song?) {
        val normalize = song?.deepCopy()?.normalize()

        val hashCode = normalize?.hashCode() ?: 0
        if (hashCode == lastDispatchSongId) return
        lastDispatchSongId = hashCode

        listeners.forEach { it.onSongChanged(normalize) }
    }

    // --- 纯状态透传 (不涉及加工) ---

    override fun onReceiveText(text: String?) {
        this.cachedRawText = text
        this.cachedSourcePackage = providerInfo?.playerPackageName
        listeners.forEach { it.onReceiveText(text) }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        this.cachedIsPlaying = isPlaying
        listeners.forEach { it.onPlaybackStateChanged(isPlaying) }
    }

    override fun onPositionChanged(position: Long) {
        this.cachedPosition = position
        listeners.forEach { it.onPositionChanged(position) }
    }

    override fun onSeekTo(position: Long) {
        listeners.forEach { it.onSeekTo(position) }
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        listeners.forEach { it.onDisplayTranslationChanged(isDisplayTranslation) }
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        listeners.forEach { it.onDisplayRomaChanged(isDisplayRoma) }
    }

    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        val previous = this.providerInfo?.playerPackageName
        val current = providerInfo?.playerPackageName
        this.providerInfo = providerInfo

        if (previous != current) {
            // 播放源发生切换：清空去重指纹，否则同一首歌（或重连后重发的快照）
            // 会被当成重复数据丢弃，下游一次 onSongChanged 都收不到
            lastDispatchSongId = NO_DISPATCHED_SONG
            Log.d(TAG, "Provider changed: $previous -> $current, reset dispatch dedup")
        }

        // 换到别的播放器：缓存里的歌属于上一个播放器，清掉避免补发时串台；
        // 断开（null）时保留缓存，用于同播放器重连后恢复歌词
        if (providerInfo != null && cachedSourcePackage != null && cachedSourcePackage != current) {
            Log.d(TAG, "Drop cached lyric of $cachedSourcePackage on provider switch")
            cachedRawSong = null
            cachedRawText = null
            cachedSourcePackage = null
        }

        listeners.forEach { it.onActiveProviderChanged(providerInfo) }
    }
}
