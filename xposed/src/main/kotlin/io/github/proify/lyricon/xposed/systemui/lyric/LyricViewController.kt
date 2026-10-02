/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.os.Handler
import android.os.SystemClock
import io.github.proify.android.extensions.crc32
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.statusbarlyric.logo.CoverStrategy
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewManager.MAIN_LOOPER
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import java.io.File

/**
 * 歌词视图核心控制器 (Lyric View Controller)
 * * 负责接收播放器状态、歌曲信息及系统 UI 变更，并将数据分发至所有已注册的状态栏控制器。
 * 实现了 [ActivePlayerListener]、[OplusCapsuleHooker.CapsuleStateChangeListener] 等核心接口。
 * * @author Tomakino
 * @since 2026
 */
object LyricViewController : ActivePlayerListener,
    OplusCapsuleHooker.CapsuleStateChangeListener,
    NotificationCoverHelper.OnCoverUpdateListener {

    private const val TAG = "LyricViewController"
    private const val DEBUG = true

    /** 当前播放状态 */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** 当前活跃播放器的包名 */
    @Volatile
    var activePackage: String = ""
        private set

    /**
     * 上一次连接过的播放器包名（断开时不清空）
     *
     * 用于判断"同播放器断开后重连"：provider 断开时 [activePackage] 会被置空，
     * 只靠它无法区分"重连"与"换了播放器"。
     */
    @Volatile
    private var lastProviderPackage: String = ""

    /**
     * 用户是否手动隐藏了歌词（点击歌词区域触发）
     *
     * 隐藏时歌词从状态栏移出，被可见性规则隐藏的状态栏组件会恢复显示。
     * 该状态对所有控制器生效，仅在停止播放或切换播放器时自动重置。
     */
    @Volatile
    var isLyricHiddenByUser: Boolean = false
        private set

    /**
     * 系统是否正在隐藏状态栏系统信息区(时钟、通知图标等)
     *
     * 状态栏被重新注入时,新控制器不会收到上一次的 disable 事件,
     * 这里缓存一份全局值供新控制器对齐,避免状态漂移。
     */
    @Volatile
    var isStatusBarContentDisabled: Boolean = false
        private set

    /** 是否显示翻译内容 */
    @Volatile
    private var isDisplayTranslation: Boolean = true

    /** 是否显示罗马音内容 */
    @Volatile
    private var isDisplayRoma: Boolean = true

    /** 当前歌曲的逻辑播放进度（毫秒） */
    @Volatile
    var currentLogicPosition: Long = 0
        private set

    /** 当前播放歌曲（已加工），供控制窗口读取歌名/歌手/歌词 */
    @Volatile
    var currentSong: Song? = null
        private set

    /** 当前纯文本歌词(无 Lrc 匹配时),同样需要供新控制器对齐状态 */
    @Volatile
    private var currentText: String? = null

    /** 用于处理 UI 刷新任务的 Handler */
    private val mainHandler by lazy { Handler(MAIN_LOOPER) }

    /** 进度日志节流时间戳 */
    private var lastPositionLogAt: Long = 0

    /** 进度日志节流间隔：进度每秒都在推，只按这个间隔打一条 */
    private const val POSITION_LOG_INTERVAL_MS = 10_000L

    /** 上次请求上游重发歌词的时间戳 */
    private var lastResendRequestAt: Long = 0

    /** 请求重发的最小间隔：状态回调非常密集，避免反复触发补发流水线 */
    private const val RESEND_REQUEST_INTERVAL_MS = 5_000L

    /** * 高频进度更新任务。
     * 使用单例 Runnable 减少 GC 压力，仅在进度变更时由主线程调度。
     */
    private val frameUpdater = Runnable {
        val controllers = StatusBarViewManager.controllers
        val awake = !isScreenOff()
        for (i in controllers.indices) {
            if (awake) controllers[i].lyricView.ensureAwake()
            controllers[i].lyricView.setPosition(currentLogicPosition)
        }
    }

    /** 屏幕是否处于灭屏状态（灭屏时不应把歌词视图从休眠态唤醒） */
    private fun isScreenOff(): Boolean =
        ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF

    init {
        if (DEBUG) YLog.debug(TAG, "Initializing LyricViewController...")
        // 注册数据总线、系统钩子及封面更新监听
        LyricDataHub.addListener(this)
        OplusCapsuleHooker.registerListener(this)
        NotificationCoverHelper.registerListener(this)
    }

    /**
     * 当歌曲发生切换时回调。
     * @param song 新歌曲对象，若停止播放则为 null
     */
    override fun onSongChanged(song: Song?) {
        YLog.info(TAG, "onSongChanged: $song")
        this.currentSong = song

        updateAllControllers {
            lyricView.setSong(song)
            refreshTranslationVisibility(lyricView)
            // 新注入的状态栏视图可能没收到过 setPlaying,借歌曲事件校正一次
            lyricView.ensurePlayingState(isPlaying)
        }

        updateCoverFileFromSong(song)
    }

    private fun updateCoverFileFromSong(song: Song?) {
        val activePackage = this.activePackage
        val name = song?.name
        val artist = song?.artist
        if (activePackage.isBlank() || name.isNullOrBlank() || artist.isNullOrBlank()) {
            return
        }
        val file = NotificationCoverHelper.getCachedCoverFile(activePackage, name, artist)
        if (file != null && file.exists()) {
            YLog.info(TAG, "Cover cache file found: $name - $artist")
            updateCoverFile(file)
        }
    }

    /**
     * 当活跃播放源发生切换时回调（如从网易云切换至 QQ 音乐）。
     * @param providerInfo 播放器信息
     */
    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        YLog.info(TAG, "onActiveProviderChanged: $providerInfo")

        val newPackage = providerInfo?.playerPackageName.orEmpty()

        // 同播放器断开后重连：视图内容在断开时已被清空，但全局缓存还在，
        // 这里走"补发"而不是"硬清空"，重连后歌词立刻回来
        val isSameProviderReconnect =
            providerInfo != null && newPackage.isNotEmpty() && newPackage == lastProviderPackage
        if (newPackage.isNotEmpty()) lastProviderPackage = newPackage

        // 切换播放源属于新一轮播放，重置用户手动隐藏状态
        setLyricHiddenByUser(false)
        this.activePackage = newPackage
        LyricPrefs.activePackageName = newPackage

        if (isSameProviderReconnect) {
            YLog.info(TAG, "Same provider reconnected ($newPackage), restoring cached lyric state")
            updateAllControllers {
                restoreStateForSameProvider(this, providerInfo)
            }
        } else {
            updateAllControllers {
                resetViewForNewPlayer(this, providerInfo)
            }
        }

        // 断开重连后上游常常只继续推进度、不再重发 onSongChanged，
        // 这里主动请求补发一次；切换播放器时补发会因包名不匹配自动放弃
        if (providerInfo != null) {
            requestLyricResend("provider-changed", newPackage)
        }
    }

    /**
     * 请求上游/本地缓存重新推送当前歌词状态
     *
     * @param reason 触发原因，仅用于日志定位
     * @param expectedPackage 期望的播放器包名，避免把上一个播放器的歌词补发到新播放器
     */
    private fun requestLyricResend(reason: String, expectedPackage: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastResendRequestAt < RESEND_REQUEST_INTERVAL_MS) {
            YLog.debug(TAG, "Skip lyric resend ($reason): throttled")
            return
        }
        lastResendRequestAt = now
        YLog.info(TAG, "Requesting lyric resend ($reason), package=$expectedPackage")
        LyricDataHub.requestCurrentSong(expectedPackage)
    }

    /**
     * 播放状态变更回调（播放/暂停）。
     * @param isPlaying 播放状态
     */
    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        YLog.info(TAG, "onPlaybackStateChanged: $isPlaying (was ${this.isPlaying})")

        // 不做全局去重:状态栏可能被重新注入(新控制器),全局去重会把状态变更吞掉,
        // 导致新视图永远收不到 setPlaying 而不显示歌词。视图内部仍有去重,不会产生冗余刷新。
        this.isPlaying = isPlaying

        // 停止播放视为一轮播放结束，重置用户手动隐藏状态，避免歌词一直不再显示
        if (!isPlaying) {
            setLyricHiddenByUser(false)
            // 歌词不再可交互，触摸通道自检没有意义，立即停掉避免后台空转
            LyricTouchRouter.pauseHealthChecks("playback-stopped")
        }

        if (isPlaying && !isScreenOff()) {
            // 长时间暂停后触摸失效的常见原因：根视图的触摸监听被 SystemUI 覆盖，
            // 或视图卡在休眠态。恢复播放时顺手把通道与休眠状态校正回来。
            LyricTouchRouter.refreshAllChannels("playback-resume")
        }

        var anyRendered = false
        updateAllControllers {
            if (isPlaying && !isScreenOff()) lyricView.ensureAwake()
            lyricView.setPlaying(isPlaying)
            // 恢复播放时兜底：暂停期间歌词行被清空，只重建数据不会重新定位，
            // 若进度推送迟到（长时间暂停后上游可能已停止推送），歌词会一直不显示。
            if (isPlaying && lyricView.ensureLyricsRendered(currentLogicPosition)) {
                anyRendered = true
            }
        }

        // 极端兜底：恢复播放后依然没有任何可显示的行，且全局也没有歌曲数据
        // （provider 断开重连、数据被清空的场景），主动请求补发一次
        if (isPlaying && !anyRendered && currentSong == null) {
            requestLyricResend("resume-without-content", activePackage.ifBlank { null })
        }
    }

    /**
     * 切换歌词的用户隐藏状态（点击歌词区域触发）
     *
     * @param hidden true 表示隐藏歌词并恢复被隐藏的状态栏组件
     */
    fun setLyricHiddenByUser(hidden: Boolean) {
        if (isLyricHiddenByUser == hidden) return
        YLog.info(TAG, "setLyricHiddenByUser: $hidden")

        isLyricHiddenByUser = hidden
        updateAllControllers { setLyricUserHidden(hidden) }
    }

    /** 在隐藏与显示之间切换歌词 */
    fun toggleLyricHiddenByUser() = setLyricHiddenByUser(!isLyricHiddenByUser)

    /**
     * 记录系统的状态栏禁用状态并分发给所有控制器
     *
     * 该全局值会用于新注入的状态栏视图对齐状态(见 [syncTo])。
     */
    fun setStatusBarContentDisabled(disabled: Boolean) {
        isStatusBarContentDisabled = disabled
        updateAllControllers { onDisableStateChanged(disabled) }
    }

    /**
     * 把当前全局状态整体推送给单个控制器
     *
     * 状态栏可能被 SystemUI 重新注入(配置变更、横竖屏、多任务等场景),
     * 新控制器持有的是全新视图,不会收到此前已经发生过的事件;若不补发,
     * 就会出现"全局在播放、新歌词视图却一直不显示"的问题。
     */
    fun syncTo(controller: StatusBarViewController) {
        StatusBarViewManager.runOnMainThread {
            runCatching {
                pushStateTo(controller)
            }.onFailure { e ->
                YLog.error(TAG, "Failed to sync state to controller", e)
            }
        }
    }

    /**
     * 把当前全局状态整体灌进单个控制器（必须在主线程调用）
     *
     * [syncTo] 用于新注入的状态栏视图，同播放器重连时复用同一套补发逻辑。
     */
    private fun pushStateTo(controller: StatusBarViewController) {
        controller.onDisableStateChanged(isStatusBarContentDisabled)

        val song = currentSong
        val text = currentText
        if (song != null) {
            controller.lyricView.setSong(song)
        } else if (text != null) {
            controller.lyricView.setText(text)
        }
        refreshTranslationVisibility(controller.lyricView)

        controller.lyricView.setPlaying(isPlaying)
        controller.lyricView.setPosition(currentLogicPosition)
        // 只装载数据不会渲染出歌词行，这里按进度兜底定位一次
        controller.lyricView.ensureLyricsRendered(currentLogicPosition)
        controller.setLyricUserHidden(isLyricHiddenByUser)

        YLog.info(
            TAG,
            "Synced state to controller: playing=$isPlaying, song=${song?.name}, " +
                    "position=$currentLogicPosition, hidden=$isLyricHiddenByUser, " +
                    "disabled=$isStatusBarContentDisabled, " +
                    "state=${controller.lyricView.dumpState()}"
        )
    }

    /**
     * 同播放器断开重连：用全局缓存把歌词灌回视图，不做硬清空
     *
     * 断开时 [resetViewForNewPlayer] 已经执行过 setSong(null)，视图里什么都不剩；
     * 而重连后上游往往只继续推进度、不再重发 onSongChanged，硬清空就等于一直空白。
     */
    private fun restoreStateForSameProvider(
        controller: StatusBarViewController,
        provider: ProviderInfo?
    ) {
        controller.updateLyricStyle(LyricPrefs.getLyricStyle())
        applyProviderLogo(controller, provider)
        pushStateTo(controller)
    }

    /**
     * 播放进度正常步进时的回调（通常每秒触发）。
     * @param position 当前逻辑时间戳
     */
    override fun onPositionChanged(position: Long) {
        val previous = this.currentLogicPosition
        this.currentLogicPosition = position

        // 进度更新极其频繁，只做节流日志：用于判断「恢复播放后上游是否还在推进度」
        val now = SystemClock.elapsedRealtime()
        if (now - lastPositionLogAt >= POSITION_LOG_INTERVAL_MS) {
            lastPositionLogAt = now
            YLog.debug(
                TAG,
                "onPositionChanged: $position (was $previous, playing=$isPlaying)"
            )
        }

        // 进度更新极其频繁，直接 post 到 Handler
        mainHandler.post(frameUpdater)
    }

    /**
     * 用户手动调整进度（Seek）时的回调。
     * @param position 目标时间戳
     */
    override fun onSeekTo(position: Long) {
        this.currentLogicPosition = position
        updateAllControllers { lyricView.seekTo(position) }
    }

    /**
     * 接收到纯文本歌词时的回调（通常用于未匹配到 Lrc 的情况）。
     * @param text 歌词文本内容
     */
    override fun onReceiveText(text: String?) {
        YLog.info(TAG, "onReceiveText: $text")
        this.currentText = text
        updateAllControllers {
            lyricView.setText(text)
            lyricView.ensurePlayingState(isPlaying)
        }
    }

    /**
     * 翻译显示开关状态变更。
     * @param isDisplayTranslation 是否开启
     */
    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        YLog.info(TAG, "onDisplayTranslationChanged: $isDisplayTranslation")

        this.isDisplayTranslation = isDisplayTranslation
        updateAllControllers { refreshTranslationVisibility(lyricView) }
    }

    /**
     * 罗马音显示开关状态变更。
     * @param isDisplayRoma 是否开启
     */
    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        YLog.info(TAG, "onDisplayRomaChanged: $isDisplayRoma")

        this.isDisplayRoma = isDisplayRoma
        updateAllControllers { lyricView.updateDisplayTranslation(displayRoma = isDisplayRoma) }
    }

    /**
     * 应用全局配置更新（如字体颜色、阴影等样式变更）。
     * @param style 新的歌词样式配置
     */
    fun applyConfigurationUpdate(style: LyricStyle) {
        updateAllControllers { updateLyricStyle(style) }
        LyricDataHub.reprocessCurrentSong()
    }

    /**
     * 针对新播放器重置视图状态。
     * @param controller 具体的控制器实例
     * @param provider 播放源信息
     */
    private fun resetViewForNewPlayer(
        controller: StatusBarViewController,
        provider: ProviderInfo?
    ) {
        val view = controller.lyricView
        view.setSong(null)
        view.setPlaying(false)
        controller.updateLyricStyle(LyricPrefs.getLyricStyle())
        view.updateVisibility()

        applyProviderLogo(controller, provider)
    }

    /** 把播放器信息同步到歌词视图的 logo 区域 */
    private fun applyProviderLogo(controller: StatusBarViewController, provider: ProviderInfo?) {
        controller.lyricView.logoView.apply {
            this.activePackage = provider?.playerPackageName.orEmpty()
            this.providerLogo = provider?.logo
        }
    }

    /**
     * 根据当前用户配置和样式决定翻译行的显示状态。
     * @param view 状态栏歌词视图
     */
    private fun refreshTranslationVisibility(view: StatusBarLyric) {
        val style = LyricPrefs.activePackageStyle
        val shouldShow = isDisplayTranslation &&
                !style.text.isDisableTranslation &&
                !style.text.isTranslationOnly
        view.updateDisplayTranslation(displayTranslation = shouldShow)
    }

    /**
     * 核心分发方法：在主线程遍历所有控制器并执行操作。
     * @param block 需要在每个控制器上执行的逻辑
     */
    private inline fun updateAllControllers(crossinline block: StatusBarViewController.() -> Unit) {
        StatusBarViewManager.forEachOnMainThread { controller ->
            runCatching {
                controller.block()
            }.onFailure { e ->
                YLog.error(TAG, "UI Update distribution error", e)
            }
        }
    }

    /**
     * Oplus (ColorOS) 胶囊状态变更监听。
     * 用于在系统胶囊出现时自动隐藏歌词，避免遮挡。
     * @param isShowing 胶囊是否正在显示
     */
    override fun onColorOsCapsuleVisibilityChanged(isShowing: Boolean) {
        updateAllControllers { lyricView.setOplusCapsuleVisibility(isShowing) }
    }

    /**
     * 专辑封面更新回调。
     * @param packageName 触发更新的播放器包名
     * @param coverFile 封面文件对象
     */
    override fun onCoverUpdated(packageName: String, coverFile: File) {
        if (packageName != activePackage) return
        updateCoverFile(coverFile)
    }

    private var lastCoverSignature = 0L
    private fun updateCoverFile(coverFile: File?) {
        if (coverFile != null) {
            val signature = coverFile.crc32()
            if (signature == lastCoverSignature) {
                YLog.verbose(TAG, "Cover file is the same, skip update")
                return
            }
            lastCoverSignature = signature
        } else {
            lastCoverSignature = 0
        }

        updateAllControllers {
            lyricView.logoView.apply {
                this.coverFile = coverFile
                (strategy as? CoverStrategy)?.updateContent()
            }
            updateCoverThemeColors(coverFile)
        }
    }
}