/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.statusbarlyric

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.contains
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import io.github.proify.android.extensions.dp
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LogoStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.view.LayoutTransitionX
import io.github.proify.lyricon.lyric.view.LyricPlayerView
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.LyricType.NONE
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.LyricType.SONG
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.LyricType.TEXT
import io.github.proify.lyricon.statusbarlyric.logo.SuperLogo
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class StatusBarLyric(
    context: Context,
    initialStyle: LyricStyle,
    linkedTextView: TextView?
) : LinearLayout(context) {

    companion object {
        const val VIEW_TAG: String = "lyricon:lyric_view"
        private const val TAG = "StatusBarLyric"

        /* ---- 触摸反馈参数 ---- */

        /** 按下时缩放到的比例 */
        private const val PRESS_DOWN_SCALE = 0.97f

        /** 长按时放大到的比例 */
        private const val LONG_PRESS_SCALE = 1.02f

        /** 按下/松手动画时长(ms) */
        private const val PRESS_DOWN_DURATION_MS = 90L
        private const val LONG_PRESS_INTENSIFY_MS = 140L
        private const val RELEASE_DURATION_MS = 170L

        /** 松手回弹阻尼(DecelerateInterpolator 系数,越大越快停下) */
        private const val RELEASE_BOUNCE = 1.5f

        /** 是否输出可见性判定明细(排查"歌词不显示"时打开) */
        private const val DEBUG_VISIBILITY = true

        /** 滑动识别的"踢出"位移(dp)与动画时长(ms) */
        private const val SWIPE_KICK_DP = 22
        private const val SWIPE_KICK_MS = 70L
        private const val SWIPE_RETURN_MS = 190L

        /**
         * 歌词隐藏(把手)状态下抬升的 Z 序
         *
         * 把手与状态栏其它组件重叠,抬升 Z 序可保证它优先收到触摸事件,
         * 避免被后添加的系统组件抢走点击。
         */
        private const val HIDDEN_HANDLE_Z = 1000f
    }

    /**
     * 手势类型 (Gesture Type)
     *
     * 由 [gestureListener] 在识别到手势时回调,供上层(如 Xposed 控制器)映射为具体动作。
     */
    enum class GestureType {
        /** 手指向左滑动 */
        SWIPE_LEFT,

        /** 手指向右滑动 */
        SWIPE_RIGHT,

        /** 单击 */
        TAP,

        /** 长按 */
        LONG_PRESS
    }

    // --- 手势控制 ---

    /** 手势回调,在主线程派发,仅 [gestureEnabled] 为 true 时触发 */
    var gestureListener: ((GestureType) -> Unit)? = null

    /**
     * 是否启用手势识别。
     *
     * 关闭时回退到 [View] 默认触摸行为(此时可通过
     * [setOnClickListener] 委托单击,保持旧版行为)。
     */
    var gestureEnabled: Boolean = true
        set(value) {
            field = value
            // 保持 clickable 语义,便于无障碍与默认触摸行为兼容
            isClickable = true
        }

    /**
     * 是否启用震动反馈(手势识别成功时触发)。
     * 由上层控制器按偏好配置,默认开启。
     */
    var hapticEnabled: Boolean = true

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold: Float = (touchSlop * 2f).coerceAtLeast(24f)

    private var gestureDownX: Float = 0f
    private var gestureDownY: Float = 0f
    private var gestureLongPressFired: Boolean = false

    /**
     * 收到的触摸事件计数(含触摸路由补发的合成事件)
     *
     * 触摸路由用它判断本视图是否"本就能收到事件":若一次手势期间计数没有变化,
     * 说明事件被上层拦截,需要由路由补发合成手势。
     */
    var nativeTouchCount: Long = 0L
        private set

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        nativeTouchCount++
        return super.dispatchTouchEvent(ev)
    }

    // 隐藏(把手)状态下的按下坐标
    private var hiddenDownX: Float = 0f
    private var hiddenDownY: Float = 0f

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (gestureLongPressFired) return true
                // 触觉反馈:单击(可关闭)
                triggerHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                performClick()
                gestureListener?.invoke(GestureType.TAP)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                gestureLongPressFired = true
                // 长按:放大提示 + 触觉反馈(可关闭)
                intensifyPressFeedback()
                triggerHaptic(HapticFeedbackConstants.LONG_PRESS)
                gestureListener?.invoke(GestureType.LONG_PRESS)
            }
        }
    )

    val logoView: SuperLogo = SuperLogo(context).apply {
        this.linkedTextView = linkedTextView
    }

    val textView: SuperText = SuperText(context).apply {
        this.linkedTextView = linkedTextView
        eventListener = object : SuperText.EventListener {
            override fun enteringInterludeMode(duration: Long) {
                logoView.syncProgress(0, duration)
            }

            override fun exitInterludeMode() {
                logoView.clearProgress()
            }
        }
    }

    // --- 对外状态 ---

    var currentStatusColor: StatusColor = StatusColor()
        private set

    var isSleepMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            Log.d(TAG, "休眠模式：$value")
            if (value) {
                pendingSleepData = PendingData()
            } else {
                pendingSleepData?.let { seekTo(it.position) }
                pendingSleepData = null
            }
        }

    // --- 样式 / 播放状态 ---

    private var currentStyle: LyricStyle = initialStyle
    private var isPlaying: Boolean = false
    private var isOplusCapsuleShowing: Boolean = false

    var onPlayingChanged: ((Boolean) -> Unit)? = null

    // 上一次 Logo gravity，用于避免重复重排
    private var lastLogoGravity: Int = -114

    // 休眠期间缓存的进度数据
    private var pendingSleepData: PendingData? = null

    // --- 歌词内容与超时状态 ---

    private var hasLyricContent: Boolean = false
    private var lyricTimedOut: Boolean = false
    private var currentLyric: String? = null

    // 主线程调度器
    private val mainHandler: Handler = Handler(context.mainLooper)

    // 当前生效的超时 Runnable
    private var lyricTimeoutTask: Runnable? = null

    /**
     * 用户手动隐藏歌词(点击歌词区域触发)
     *
     * 为 true 时歌词折叠为一个"把手":内容全部隐藏、视觉完全透明,
     * 并通过负的 [rightMargin] 把占位抵消为 0,状态栏空间让给被可见性规则隐藏的
     * 组件(时钟、通知图标等);把手仍覆盖歌词原有区域,点击即恢复歌词。
     */
    var userHidden: Boolean = false
        set(value) {
            if (field == value) return
            Log.d(TAG, "用户隐藏歌词：$value")
            field = value
            applyHiddenState(value)
        }

    /**
     * 无条件同步隐藏(把手)状态
     *
     * 状态栏被重新注入时,新视图的 [userHidden] 与全局记录可能不一致,
     * 用这个方法强制对齐一次,避免"全局认为已隐藏、视图却在显示"(或反之)
     * 导致点击切换失效、歌词不再显示。
     */
    fun syncHiddenState(hidden: Boolean) {
        userHidden = hidden
        applyHiddenState(hidden)
    }

    /** 歌词处于隐藏(把手)状态时,点击把手的回调(用于恢复歌词) */
    var onHiddenClick: (() -> Unit)? = null

    // 跟随系统隐藏状态栏内容
    var isDisabledVisible = false
        set(value) {
            field = value
            updateVisibility()

            //修复在歌词播放结束时，来回切换StatusBarLyric可见性，导致颜色异常透明问题
            val old = currentStatusColor
            setStatusBarColor(StatusColor())
            setStatusBarColor(old)
        }

    // --- 系统 / 辅助组件 ---

    private val keyguardManager: KeyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    /**
     * 单次布局变更动画
     * 用于样式或尺寸突变时的过渡
     */
    private val singleLayoutTransition: LayoutTransition = LayoutTransitionX().apply {
        addTransitionListener(object : LayoutTransition.TransitionListener {

            override fun startTransition(
                transition: LayoutTransition?, container: ViewGroup?,
                view: View?, transitionType: Int
            ) = Unit

            override fun endTransition(
                transition: LayoutTransition?, container: ViewGroup?,
                view: View?, transitionType: Int
            ) {
                disableTransitionType(LayoutTransition.CHANGING)
                layoutTransition = null
            }
        })
    }

    private val singleVisibilityLayoutTransition: LayoutTransition = LayoutTransitionX().apply {
        setDuration(500)
        addTransitionListener(object : LayoutTransition.TransitionListener {

            override fun startTransition(
                transition: LayoutTransition?, container: ViewGroup?,
                view: View?, transitionType: Int
            ) = Unit

            override fun endTransition(
                transition: LayoutTransition?, container: ViewGroup?,
                view: View?, transitionType: Int
            ) {
                disableTransitionType(LayoutTransition.CHANGING)
                layoutTransition = null
            }
        })
    }

    // TextView 子视图结构变化监听，用于刷新可见性
    private val textHierarchyChangeListener = object : OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View?, child: View?) = updateVisibility()
        override fun onChildViewRemoved(parent: View?, child: View?) = updateVisibility()
    }

    // 歌词变化监听，用于重置超时逻辑
    private val lyricCountChangeListener =
        object : LyricPlayerView.LyricCountChangeListener {

            override fun onLyricTextChanged(old: String, new: String) {
                currentLyric = new
                refreshLyricTimeoutState()
            }

            override fun onLyricChanged(
                news: List<IRichLyricLine>,
                removes: List<IRichLyricLine>
            ) {
                currentLyric = news.lastOrNull()?.text
                refreshLyricTimeoutState()
            }
        }

    init {
        tag = VIEW_TAG
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        layoutTransition = null

        addView(
            textView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT)
                .apply {
                    weight = 1f
                }
        )

        updateLogoLocation()
        applyInitialStyle(initialStyle)

        textView.setOnHierarchyChangeListener(textHierarchyChangeListener)
        textView.lyricCountChangeListeners += lyricCountChangeListener
    }

    // --- 公开 API ---

    fun updateStyle(style: LyricStyle) {
        triggerSingleTransition()
        currentStyle = style
        logoView.applyStyle(style)
        updateLogoLocation()
        textView.applyStyle(style)
        updateLayoutConfig(style)

        refreshLyricTimeoutState()
        requestLayout()
    }

    fun setStatusBarColor(color: StatusColor) {
        currentStatusColor = color
        logoView.setStatusBarColor(color)
        textView.setStatusBarColor(color)
    }

    private var lastPlaying: Boolean? = null
    private var lastSong: Song? = null
    private var lastText: String? = null
    private var lyricType = NONE

    /**
     * 最近一次已知的播放进度
     *
     * 暂停时歌词行会被 [SuperText.reset] 清空，视图里不再有任何可显示的行；
     * 恢复播放时只重建歌词数据并不会重新定位，必须靠这里记录的进度再做一次 seek，
     * 否则歌词会一直停在"无行可显示"的状态，直到下一次进度推送才恢复
     * （长时间暂停后上游可能已停止推送进度，那就再也不显示了）。
     */
    private var lastPosition: Long = 0

    fun setPlaying(playing: Boolean) {
        if (lastPlaying == playing) return
        Log.d(TAG, "setPlaying: $playing")

        lastPlaying = playing
        isPlaying = playing
        onPlayingChanged?.invoke(playing)

        if (!playing) {
            textView.reset()
        } else {
            when (lyricType) {
                NONE -> Unit
                SONG -> setSong(lastSong)
                TEXT -> setText(lastText)
            }
            // 暂停期间歌词行已被清空，这里按暂停前的进度重新定位，
            // 让歌词在恢复播放的那一刻就回来，而不是等下一次进度推送。
            if (lastPosition > 0) seekTo(lastPosition)
        }

        Log.d(
            TAG,
            "setPlaying: $playing (type=$lyricType, hasSong=${lastSong != null}, " +
                    "hasText=${lastText != null}, lastPosition=$lastPosition)"
        )

        refreshLyricTimeoutState()
        updateVisibility()
    }

    /**
     * 校正播放状态(不重新装载歌词内容)
     *
     * 状态栏被重新注入后,新视图可能没有收到此前的 setPlaying 事件,
     * 导致"全局在播放、视图却认为未播放"。这里只对齐状态并重新评估超时与可见性,
     * 避免重复装载歌词造成闪烁。
     */
    fun ensurePlayingState(playing: Boolean) {
        if (lastPlaying == playing) return
        Log.d(TAG, "ensurePlayingState: $playing")

        lastPlaying = playing
        isPlaying = playing
        onPlayingChanged?.invoke(playing)

        refreshLyricTimeoutState()
    }

    /**
     * 兜底：确保歌词已经按给定进度渲染出来
     *
     * 控制层持有全局的播放进度，而视图可能因为状态栏被重新注入等原因丢失过
     * [lastPosition]。若歌词行已经渲染出来就直接跳过，避免无谓的重复定位。
     */
    fun ensureLyricsRendered(position: Long) {
        if (position <= 0) return
        if (textView.shouldShow()) return
        Log.d(TAG, "ensureLyricsRendered: seek to $position")
        seekTo(position)
    }

    fun isHideOnLockScreen() =
        currentStyle.basicStyle.hideOnLockScreen && keyguardManager.isKeyguardLocked

    val enableEnterAnim get() = currentStyle.packageStyle.text.enableEnterAnim
    private var lastDisabledVisible: Boolean = false
    fun updateVisibility() {
        // 隐藏态:不显示歌词内容,只保留可点击的把手(锁屏 / 系统隐藏状态栏时把手也隐藏)
        if (userHidden) {
            isVisible = !isHideOnLockScreen() && !isDisabledVisible
            lastDisabledVisible = isDisabledVisible
            return
        }

        val shouldShow = isPlaying
                && !isHideOnLockScreen()
                && textView.shouldShow()
                && !lyricTimedOut
                && !isDisabledVisible

        logVisibilityState(shouldShow)

        if (shouldShow == isVisible) {
            return
        }

        if (enableEnterAnim && shouldShow && !lastDisabledVisible) {
            isVisible = shouldShow
            postDelayed({
                triggerSingleVisibilityLayoutTransition()
                logoView.forceHide = false
            }, 0)
        } else {
            isVisible = shouldShow
            logoView.forceHide = enableEnterAnim && !shouldShow && !isDisabledVisible
        }

        lastDisabledVisible = isDisabledVisible
    }

    /**
     * 输出本次可见性判定的完整明细
     *
     * 歌词"不显示"可能来自多个互斥条件(播放状态、超时、系统禁用状态栏、锁屏、
     * 以及手动隐藏残留的透明状态),这里一次性打全,便于从日志直接定位原因。
     */
    private fun logVisibilityState(shouldShow: Boolean) {
        if (!DEBUG_VISIBILITY) return
        Log.d(
            TAG, "visibility: shouldShow=$shouldShow, isVisible=$isVisible | " +
                    "playing=$isPlaying, userHidden=$userHidden, timedOut=$lyricTimedOut, " +
                    "disabled=$isDisabledVisible, lockScreenHide=${isHideOnLockScreen()}, " +
                    "sleep=$isSleepMode, textShouldShow=${textView.shouldShow()}, " +
                    "alpha=$alpha, w=$width, translationX=$translationX"
        )
    }

    /**
     * 导出当前可见性与内容状态，供「导出日志」采集诊断信息。
     *
     * 与 [logVisibilityState] 用的是同一批判定条件，只是对外以字符串形式返回。
     */
    fun dumpState(): String = buildString {
        append("visible=$isVisible")
        append(", playing=$isPlaying")
        append(", userHidden=$userHidden")
        append(", timedOut=$lyricTimedOut")
        append(", hasContent=$hasLyricContent")
        append(", disabled=$isDisabledVisible")
        append(", lockScreenHide=${isHideOnLockScreen()}")
        append(", sleep=$isSleepMode")
        append(", textShouldShow=${textView.shouldShow()}")
        append(", type=$lyricType")
        append(", lastPosition=$lastPosition")
        append(", alpha=$alpha")
        append(", size=${width}x${height}")
        append(", translationX=$translationX")
        append(", lyric='${currentLyric?.take(40)}'")
        append(", attached=$isAttachedToWindow")
    }

    /**
     * 应用 / 取消歌词的隐藏(把手)状态
     *
     * @param hidden true 时折叠为透明把手(净占位 0,仍覆盖原歌词区域接收点击)
     */
    private fun applyHiddenState(hidden: Boolean) {
        if (hidden) {
            val width = calculateContainerWidth(currentStyle.basicStyle)
            ensureLayoutParams().apply {
                this.width = width
                leftMargin = 0
                topMargin = 0
                bottomMargin = 0
                // 负的右外边距抵消自身宽度:净占位为 0,空间全部让给状态栏组件,
                // 而把手仍覆盖歌词原有区域以接收点击
                rightMargin = -width
            }
            logoView.visibility = GONE
            textView.visibility = GONE
            alpha = 0f
            translationZ = HIDDEN_HANDLE_Z
        } else {
            logoView.visibility = VISIBLE
            textView.visibility = VISIBLE
            alpha = 1f
            translationZ = 0f
            // 还原宽度、外边距与内边距
            updateLayoutConfig(currentStyle)
        }
        requestLayout()
        updateVisibility()
    }

    fun setSong(song: Song?) {
        lyricType = SONG
        lastSong = song
        textView.song = song
        hasLyricContent = !song?.lyrics.isNullOrEmpty()
        refreshLyricTimeoutState()
    }

    fun setText(text: String?) {
        lyricType = TEXT
        lastText = text

        textView.text = text
        hasLyricContent = !text.isNullOrBlank()
        refreshLyricTimeoutState()
    }

    fun seekTo(position: Long) {
        lastPosition = position
        if (isSleepMode) {
            pendingSleepData?.position = position
            return
        }

        textView.seekTo(position)
        refreshLyricTimeoutState()
    }

    fun setPosition(position: Long) {
        lastPosition = position
        if (isSleepMode) {
            pendingSleepData?.position = position
            return
        }

        textView.setPosition(position)
    }

    fun updateDisplayTranslation(
        displayTranslation: Boolean = textView.isDisplayTranslation,
        displayRoma: Boolean = textView.isDisplayRoma
    ) {
        textView.updateDisplayTranslation(displayTranslation, displayRoma)
    }

    fun setOplusCapsuleVisibility(visible: Boolean) {
        isOplusCapsuleShowing = visible
        triggerSingleTransition()
        updateWidthInternal(currentStyle)
        logoView.isOplusCapsuleShowing = visible
    }

    // --- 手势识别与触摸反馈 ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 隐藏态:把手不参与手势映射,区域内的点击一律用于恢复歌词
        if (userHidden) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hiddenDownX = event.x
                    hiddenDownY = event.y
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.x - hiddenDownX) > touchSlop ||
                            abs(event.y - hiddenDownY) > touchSlop
                    if (!moved) {
                        triggerHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        onHiddenClick?.invoke()
                    }
                }
            }
            return true
        }

        if (!gestureEnabled) {
            // 手势关闭:交给系统默认触摸行为,由点击监听器处理单击
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = event.x
                gestureDownY = event.y
                gestureLongPressFired = false
                startPressFeedback()
            }

            MotionEvent.ACTION_MOVE -> Unit

            MotionEvent.ACTION_UP -> {
                val dx = event.x - gestureDownX
                val dy = event.y - gestureDownY

                // 手动滑动判定:不依赖 Fling 速度,慢速横向拖拽同样生效;
                // 长按已触发时不再判定滑动,避免一次手势同时触发两个动作
                if (!gestureLongPressFired && abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.5f) {
                    playSwipeFeedback(dx)
                    triggerHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    gestureListener?.invoke(
                        if (dx < 0) GestureType.SWIPE_LEFT else GestureType.SWIPE_RIGHT
                    )
                } else {
                    releasePressFeedback()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                releasePressFeedback()
            }
        }

        return gestureDetector.onTouchEvent(event)
    }

    /**
     * 按下反馈:轻微按压缩放
     */
    private fun startPressFeedback() {
        if (width > 0 && height > 0) {
            pivotX = width / 2f
            pivotY = height / 2f
        }

        animate()
            .scaleX(PRESS_DOWN_SCALE).scaleY(PRESS_DOWN_SCALE)
            .setDuration(PRESS_DOWN_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .start()
    }

    /**
     * 长按反馈:轻微放大提示
     */
    private fun intensifyPressFeedback() {
        animate()
            .scaleX(LONG_PRESS_SCALE).scaleY(LONG_PRESS_SCALE)
            .setDuration(LONG_PRESS_INTENSIFY_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * 松手/取消反馈:缩放与位移回弹
     */
    private fun releasePressFeedback() {
        animate()
            .scaleX(1f).scaleY(1f).translationX(0f)
            .setDuration(RELEASE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(RELEASE_BOUNCE))
            .start()
    }

    /**
     * 滑动反馈:向滑动方向"踢"一下再回弹
     *
     * @param dx 滑动结束时的横向位移(带符号)
     */
    private fun playSwipeFeedback(dx: Float) {
        val direction = if (dx < 0) -1f else 1f
        val kick = SWIPE_KICK_DP.dp * direction

        animate()
            .scaleX(1f).scaleY(1f).translationX(kick)
            .setDuration(SWIPE_KICK_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                animate().translationX(0f)
                    .setDuration(SWIPE_RETURN_MS)
                    .setInterpolator(DecelerateInterpolator(RELEASE_BOUNCE))
                    .start()
            }
            .start()
    }

    /**
     * 触觉反馈统一入口:仅 [hapticEnabled] 开启时震动
     */
    private fun triggerHaptic(feedbackConstant: Int) {
        if (hapticEnabled) {
            performHapticFeedback(feedbackConstant)
        }
    }

    // --- 内部逻辑 ---

    private fun applyInitialStyle(style: LyricStyle) {
        currentStyle = style
        logoView.applyStyle(style)
        textView.applyStyle(style)
        updateLayoutConfig(style)
    }

    private fun updateLogoLocation() {
        val logoStyle = currentStyle.packageStyle.logo
        val gravity = logoStyle.gravity

        if (gravity == lastLogoGravity) return; lastLogoGravity = gravity

        if (contains(logoView)) removeView(logoView)
        val textIndex = indexOfChild(textView).coerceAtLeast(0)

        when (gravity) {
            LogoStyle.GRAVITY_START -> addView(logoView, textIndex)
            LogoStyle.GRAVITY_END -> addView(logoView, textIndex + 1)
            else -> addView(logoView, textIndex)
        }
    }

    private fun updateLayoutConfig(style: LyricStyle) {
        val basic = style.basicStyle
        val margins = basic.margins
        val paddings = basic.paddings

        ensureLayoutParams().apply {
            width = calculateContainerWidth(basic)
            leftMargin = margins.left.dp
            topMargin = margins.top.dp
            rightMargin = margins.right.dp
            bottomMargin = margins.bottom.dp
        }
        updateTextViewWidthMode()

        updatePadding(
            paddings.left.dp,
            paddings.top.dp,
            paddings.right.dp,
            paddings.bottom.dp
        )
    }

    private fun updateWidthInternal(style: LyricStyle) {
        val width = calculateContainerWidth(style.basicStyle)
        ensureLayoutParams().width = width
        requestLayout()
        Log.d(TAG, "updateWidthInternal: $width")
    }

    private fun calculateContainerWidth(basicStyle: BasicStyle): Int {
        val isLandScape = isLandScape()
        return basicStyle.getAutoWidth(isLandScape, isOplusCapsuleShowing).dp
    }

    private fun updateTextViewWidthMode() {
        val lp = (textView.layoutParams as? LayoutParams)
            ?: LayoutParams(0, LayoutParams.WRAP_CONTENT)
        lp.width = 0
        lp.weight = 1f
        textView.layoutParams = lp
    }

    private fun ensureLayoutParams(): LayoutParams {
        val lp = layoutParams as? LayoutParams
            ?: LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
        if (layoutParams == null) layoutParams = lp
        return lp
    }

    private fun triggerSingleTransition() {
        singleLayoutTransition.enableTransitionType(LayoutTransition.CHANGING)
        layoutTransition = singleLayoutTransition
    }

    private fun triggerSingleVisibilityLayoutTransition() {
        singleVisibilityLayoutTransition.enableTransitionType(LayoutTransition.CHANGING)
        layoutTransition = singleVisibilityLayoutTransition
    }

    private fun refreshLyricTimeoutState() {
        resetLyricTimeout()

        val basicStyleConfig = currentStyle.basicStyle

        val noLyricTimeoutSec = basicStyleConfig.noLyricHideTimeout
        val noUpdateTimeoutSec = basicStyleConfig.noUpdateHideTimeout
        val keywordTimeoutSec = basicStyleConfig.keywordHideTimeout

        val shouldHideWhenNoLyric = noLyricTimeoutSec > 0
        val shouldHideWhenNoUpdate = noUpdateTimeoutSec > 0
        val shouldHideWhenKeywordMatched = keywordTimeoutSec > 0

        val timeoutSec = when {
            shouldHideWhenNoLyric && !hasLyricContent -> noLyricTimeoutSec

            hasLyricContent -> {
                val keywordMatched =
                    shouldHideWhenKeywordMatched && !currentLyric.isNullOrEmpty() &&
                            basicStyleConfig.keywordsHidePattern.orEmpty()
                                .any { it.containsMatchIn(currentLyric.orEmpty()) }

                when {
                    keywordMatched -> keywordTimeoutSec
                    shouldHideWhenNoUpdate -> noUpdateTimeoutSec
                    else -> -1
                }
            }

            else -> -1
        }

        if (timeoutSec > 0) {
            val timeoutRunnable = Runnable {
                lyricTimedOut = true
                updateVisibility()
            }
            lyricTimeoutTask = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, timeoutSec * 1000L)
        }

        updateVisibility()
    }

    private fun resetLyricTimeout() {
        lyricTimedOut = false
        lyricTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        lyricTimeoutTask = null
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateWidthInternal(currentStyle)
    }

    private fun isLandScape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private class PendingData(var position: Long = 0)

    private enum class LyricType {
        NONE, SONG, TEXT
    }
}