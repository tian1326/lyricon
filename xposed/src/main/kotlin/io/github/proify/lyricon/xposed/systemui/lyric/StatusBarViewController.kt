/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import io.github.proify.android.extensions.dp
import io.github.proify.android.extensions.isLandScape
import io.github.proify.android.extensions.setColorAlpha
import io.github.proify.android.extensions.toBitmap
import io.github.proify.lyricon.app.bridge.AppBridge.LyricGesturePrefs
import io.github.proify.lyricon.colorextractor.palette.ColorExtractor
import io.github.proify.lyricon.colorextractor.palette.ColorPaletteResult
import io.github.proify.lyricon.common.util.ResourceMapper
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.ClockViewFinder
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController.isPlaying
import io.github.proify.lyricon.xposed.systemui.lyric.control.LyricControlPopup
import io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener
import io.github.proify.lyricon.xposed.systemui.util.ViewVisibilityController
import java.io.File

/**
 * 状态栏歌词视图控制器：负责歌词视图的注入、位置锚定及显隐逻辑
 */
@SuppressLint("DiscouragedApi")
class StatusBarViewController(
    val statusBarView: ViewGroup,
    var currentLyricStyle: LyricStyle
) : ScreenStateMonitor.ScreenStateListener {
    companion object {
        const val TAG = "StatusBarViewController"
    }

    val context: Context = statusBarView.context.applicationContext
    val visibilityController: ViewVisibilityController = ViewVisibilityController(statusBarView)
    val lyricView: StatusBarLyric by lazy { createLyricView(currentLyricStyle) }

    /**
     * 触摸路由:在状态栏根视图层面接管歌词区域的触摸并转发给歌词视图。
     *
     * 部分 ROM 的状态栏存在更高层级的视图拦截或遮挡触摸,歌词视图自身收不到事件。
     */
    private val touchRouter: LyricTouchRouter = LyricTouchRouter(statusBarView) { lyricView }

    // --- 手势控制状态 (随偏好热更新) ---
    private var gestureEnabled: Boolean = LyricGesturePrefs.DEFAULT_ENABLED
    private var swipeLeftAction: Int = LyricGesturePrefs.DEFAULT_SWIPE_LEFT
    private var swipeRightAction: Int = LyricGesturePrefs.DEFAULT_SWIPE_RIGHT
    private var tapAction: Int = LyricGesturePrefs.DEFAULT_TAP
    private var longPressAction: Int = LyricGesturePrefs.DEFAULT_LONG_PRESS

    private var lastAnchor = ""
    private var lastInsertionOrder = -1
    private var internalRemoveLyricViewFlag = false
    private var lastHighlightView: View? = null
    private var colorMonitorView: View? = null
    private var coverColorPaletteResult: ColorPaletteResult? = null
    private var systemStatusBarColor: SystemStatusBarColor? = null

    private val colorChangeListener = object : OnColorChangeListener {

        private var colorFingerprint: String? = null
        override fun onColorChanged(color: Int, darkIntensity: Float) {
            val colorFingerprint = color.toString() + darkIntensity
            if (colorFingerprint == this.colorFingerprint) return
            this.colorFingerprint = colorFingerprint

            updateStatusColor(SystemStatusBarColor(color, darkIntensity))
        }
    }

    private val onGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        applyVisibilityRulesNow()
//        // 无副作用复核:布局事件也是状态栏颜色可能的变更时机(补救观察点)
//        StatusBarColorMonitor.refresh()
    }

    // --- 生命周期与初始化 ---
    fun onCreate() {
        statusBarView.addOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.addOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.addListener(this)
        lyricView.onPlayingChanged = { _ -> }

        // 手势控制:读取偏好并绑定回调,手势动作可配置
        refreshGestureConfig()
        lyricView.gestureListener = { gesture -> onLyricGesture(gesture) }
        // 歌词被手动隐藏后,点击把手恢复显示
        lyricView.onHiddenClick = { LyricViewController.setLyricHiddenByUser(false) }

        StatusBarColorMonitor.bindStatusBar(statusBarView)
        colorMonitorView = getClockView()
        StatusBarColorMonitor.bindClockView(colorMonitorView)
        StatusBarColorMonitor.addListener(colorChangeListener)

        // 接管歌词区域的触摸(绕过 ROM 上层视图的拦截/遮挡)
        touchRouter.attach()

        statusBarView.doOnAttach { checkLyricViewExists() }
        YLog.info(tag = TAG, "Lyric view created for $statusBarView")
    }

    fun onDestroy() {
        statusBarView.removeOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.removeOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.removeListener(this)
        lyricView.onPlayingChanged = null
        lyricView.gestureListener = null
        lyricView.onHiddenClick = null
        lyricView.setOnClickListener(null)
        touchRouter.detach()
        LyricControlPopup.dismissIfOwnedBy(lyricView)
        StatusBarColorMonitor.removeListener(colorChangeListener)
        colorMonitorView?.let { StatusBarColorMonitor.unbindClockView(it) }
        colorMonitorView = null
        YLog.info(tag = TAG, "Lyric view destroyed for $statusBarView")
    }

    // --- 核心业务逻辑 ---

    /**
     * 更新状态栏颜色，内部决定最终颜色
     */
    internal fun updateStatusColor(systemStatusBarColor: SystemStatusBarColor) {
        this.systemStatusBarColor = systemStatusBarColor

        val textStyle = currentLyricStyle.packageStyle.text
        lyricView.apply {
            currentStatusColor.apply {
                this.darkIntensity = systemStatusBarColor.darkIntensity

                val coverColorPaletteResult = coverColorPaletteResult
                when {
                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor
                            && textStyle.enableExtractCoverTextGradient -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val gradient = themeColors.swatches

                        this.color = gradient
                        this.translucentColor = gradient.map {
                            it.setColorAlpha(0.75f)
                        }.toIntArray()
                    }

                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val primary = themeColors.primary

                        this.color = intArrayOf(primary)
                        this.translucentColor = intArrayOf(primary.setColorAlpha(0.75f))
                    }

                    else -> {
                        this.color = intArrayOf(systemStatusBarColor.color)
                        this.translucentColor =
                            intArrayOf(systemStatusBarColor.color.setColorAlpha(0.5f))
                    }
                }
            }
            setStatusBarColor(currentStatusColor)
        }
    }

    /**
     * 更新歌词样式及位置，若锚点或顺序变化则重新注入视图
     */
    fun updateLyricStyle(lyricStyle: LyricStyle) {
        this.currentLyricStyle = lyricStyle
        val basicStyle = lyricStyle.basicStyle

        val needUpdateLocation = lastAnchor != basicStyle.anchor
                || lastInsertionOrder != basicStyle.insertionOrder
                || !lyricView.isAttachedToWindow

        if (needUpdateLocation) {
            YLog.info(
                TAG,
                "Lyric location changed: ${basicStyle.anchor}, order ${basicStyle.insertionOrder}"
            )
            updateLocation(basicStyle)
        }
        lyricView.updateStyle(lyricStyle)
        refreshGestureConfig()

        systemStatusBarColor?.let { updateStatusColor(it) }
    }

    fun updateCoverThemeColors(coverFile: File?) {
        coverColorPaletteResult = null
        try {
            val bitmap = coverFile?.toBitmap() ?: return
            ColorExtractor.extractAsync(
                bitmap = bitmap,
                cacheKey = {
                    coverFile.name
                }) {
                coverColorPaletteResult = it
                systemStatusBarColor?.let { updateStatusColor(it) }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            YLog.error(TAG, "Failed to extract cover theme colors", e)
        }
    }

    /**
     * 处理视图注入逻辑：根据 BasicStyle 寻找锚点并插入歌词视图
     */
    private fun updateLocation(baseStyle: BasicStyle) {
        val anchor = baseStyle.anchor
        val anchorId = context.resources.getIdentifier(anchor, "id", context.packageName)
        val anchorView = statusBarView.findViewById<View>(anchorId) ?: return run {
            YLog.error(TAG, "Lyric anchor view $anchor not found")
        }

        val anchorParent = anchorView.parent as? ViewGroup ?: return run {
            YLog.error(TAG, "Lyric anchor parent not found")
        }

        // 标记内部移除，避免触发冗余的 detach 逻辑
        internalRemoveLyricViewFlag = true

        (lyricView.parent as? ViewGroup)?.removeView(lyricView)

        val anchorIndex = anchorParent.indexOfChild(anchorView)

        val lp = lyricView.layoutParams ?: run {
            val width = baseStyle.getAutoWidth(
                context.isLandScape(),
                isOplusCapsuleShowing = OplusCapsuleHooker.isShowing
            ).dp

            ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 执行插入：在前或在后
        val targetIndex =
            if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_AFTER) anchorIndex + 1
            else anchorIndex
        anchorParent.addView(lyricView, targetIndex, lp)

        lyricView.updateVisibility()
        lastAnchor = anchor
        lastInsertionOrder = baseStyle.insertionOrder
        internalRemoveLyricViewFlag = false

        YLog.info(TAG, "Lyric injected: anchor $anchor, index $targetIndex")
    }

    fun checkLyricViewExists() {
        if (lyricView.isAttachedToWindow) return
        lastAnchor = ""
        lastInsertionOrder = -1
        updateLyricStyle(currentLyricStyle)
    }

    // --- 辅助方法 ---

    private fun getClockView(): View? = ClockViewFinder.find(statusBarView)

    private var wasPlayingBeforeVisibilityUpdate: Boolean = false

    fun computeShouldApplyPlayingRules(): Boolean {
        // 歌词被手动隐藏时把手仍可见,但规则应按"未在显示歌词"处理,放行状态栏组件
        if (lyricView.userHidden) return false
        return isPlaying && when {
            lyricView.isDisabledVisible -> !lyricView.isHideOnLockScreen()
            lyricView.isVisible -> true
            else -> false
        }
    }

    private fun applyVisibilityRulesNow() {
        val isPlaying = computeShouldApplyPlayingRules()
        fun apply() {
            visibilityController.applyVisibilityRules(
                rules = currentLyricStyle.basicStyle.visibilityRules,
                isPlaying = isPlaying
            )
        }

        if (!isPlaying) {
            // 仅在之前是播放状态时才更新（避免重复更新非播放状态的隐藏逻辑）
            if (wasPlayingBeforeVisibilityUpdate) {
                apply()
                wasPlayingBeforeVisibilityUpdate = false
            }
        } else {
            apply()
            wasPlayingBeforeVisibilityUpdate = true
        }
    }

    private fun createLyricView(style: LyricStyle) =
        StatusBarLyric(context, style, getClockView() as? TextView)

    // --- 歌词手动隐藏 / 恢复 ---

    /**
     * 切换歌词的用户隐藏状态。
     *
     * 隐藏时歌词折叠为透明把手(不占状态栏空间),被可见性规则隐藏的组件(时钟、通知图标等)
     * 随之恢复显示;把手覆盖歌词原有区域,点击它即恢复歌词。
     *
     * @param hidden true 表示隐藏歌词并放行状态栏组件
     */
    fun setLyricUserHidden(hidden: Boolean) {
        // 用无条件同步:全局状态与视图状态可能因状态栏重新注入而不一致,
        // 若此时只做"值相同就跳过"的赋值,残留的透明把手会让歌词再也不显示
        lyricView.syncHiddenState(hidden)
        applyVisibilityRulesNow()

        YLog.info(TAG, "Lyric hidden by user: $hidden")
    }

    // --- 手势控制 ---

    /**
     * 从偏好刷新手势配置,并同步视图的手势开关与点击行为。
     *
     * 手势关闭时保留旧版"单击打开控制面板"的行为(通过点击监听器委托)。
     */
    private fun refreshGestureConfig() {
        gestureEnabled = LyricPrefs.gestureEnabled
        swipeLeftAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_SWIPE_LEFT,
            LyricGesturePrefs.DEFAULT_SWIPE_LEFT
        )
        swipeRightAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_SWIPE_RIGHT,
            LyricGesturePrefs.DEFAULT_SWIPE_RIGHT
        )
        tapAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_TAP,
            LyricGesturePrefs.DEFAULT_TAP
        )
        longPressAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_LONG_PRESS,
            LyricGesturePrefs.DEFAULT_LONG_PRESS
        )

        lyricView.gestureEnabled = gestureEnabled
        lyricView.hapticEnabled = LyricPrefs.gestureHapticEnabled
        if (gestureEnabled) {
            lyricView.setOnClickListener(null)
        } else {
            // 关闭手势时也沿用"单击"配置的动作,默认仍是打开控制面板
            lyricView.setOnClickListener { v ->
                if (tapAction == LyricGesturePrefs.ACTION_NONE) {
                    LyricControlPopup.show(v)
                } else {
                    performGestureAction(tapAction)
                }
            }
        }
        // setOnClickListener(null) 会关闭 clickable,这里恢复以保持手势模式下的点击语义(无障碍)
        lyricView.isClickable = true
    }

    /** 执行手势映射后的具体动作 */
    private fun performGestureAction(action: Int) {
        when (action) {
            LyricGesturePrefs.ACTION_NONE -> Unit
            LyricGesturePrefs.ACTION_TOGGLE_PLAY -> PlaybackControl.togglePlay()
            LyricGesturePrefs.ACTION_PREVIOUS -> PlaybackControl.previous()
            LyricGesturePrefs.ACTION_NEXT -> PlaybackControl.next()
            LyricGesturePrefs.ACTION_OPEN_CONTROL -> LyricControlPopup.show(lyricView)
            LyricGesturePrefs.ACTION_TOGGLE_LYRIC_VISIBILITY ->
                LyricViewController.toggleLyricHiddenByUser()

            else -> YLog.warning(TAG, "Unknown gesture action: $action")
        }
    }

    /**
     * 手势回调入口:根据当前配置将手势映射为动作并执行
     */
    private fun onLyricGesture(gesture: StatusBarLyric.GestureType) {
        if (!gestureEnabled) return

        val action = when (gesture) {
            StatusBarLyric.GestureType.SWIPE_LEFT -> swipeLeftAction
            StatusBarLyric.GestureType.SWIPE_RIGHT -> swipeRightAction
            StatusBarLyric.GestureType.TAP -> tapAction
            StatusBarLyric.GestureType.LONG_PRESS -> longPressAction
        }

        performGestureAction(action)
    }

    fun highlightView(idName: String?) {
        YLog.info(TAG, "Highlighting view id:$idName")

        lastHighlightView?.background = null
        if (idName.isNullOrBlank()) return

        val id = ResourceMapper.getIdByName(context, idName)
        statusBarView.findViewById<View>(id)?.let { view ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor("#FF3582FF".toColorInt())
                cornerRadius = 20.dp.toFloat()
            }
            lastHighlightView = view
        } ?: YLog.error(TAG, "Highlight target $idName not found")
    }

    private val lyricAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            YLog.info(TAG, "LyricView attached")
        }

        override fun onViewDetachedFromWindow(v: View) {
            YLog.info(TAG, "LyricView detached")
            if (!internalRemoveLyricViewFlag) {
                checkLyricViewExists()
            } else {
                YLog.info(TAG, "LyricView detached by internal flag")
            }
        }
    }

    private val statusBarAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {}
    }

    override fun onScreenOn() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
        // 灭屏/亮屏会让 SystemUI 重建状态栏的部分视图与监听器，触摸通道可能失效
        LyricTouchRouter.refreshAllChannels("screen-on")
    }

    override fun onScreenOff() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = true
        LyricTouchRouter.pauseHealthChecks("screen-off")
    }

    override fun onScreenUnlocked() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
        LyricTouchRouter.refreshAllChannels("screen-unlocked")
    }

    fun onDisableStateChanged(shouldHide: Boolean) {
        lyricView.isDisabledVisible = shouldHide
    }

    override fun equals(other: Any?): Boolean =
        (this === other) ||
                (other is StatusBarViewController && statusBarView == other.statusBarView)

    override fun hashCode(): Int = 31 * 17 + statusBarView.hashCode()

    data class SystemStatusBarColor(val color: Int, val darkIntensity: Float)
}