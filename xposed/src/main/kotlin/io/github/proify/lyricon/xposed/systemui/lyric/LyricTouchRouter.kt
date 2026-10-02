/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.xposed.ModuleEntry
import io.github.proify.lyricon.xposed.logger.YLog
import kotlin.math.abs
import kotlin.math.max
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 歌词触摸路由 (LyricTouchRouter)
 *
 * 歌词视图嵌在状态栏较深的层级中,部分 ROM 上存在更高层级的视图拦截或遮挡触摸,
 * 导致歌词视图收不到任何事件。本类在状态栏根视图层面**观察**触摸,命中歌词区域时,
 * 在手势结束时向歌词视图补发一次合成手势(DOWN + UP),使其点击/滑动逻辑生效。
 *
 * 设计要点:
 * - **只观察、不消费**:事件始终交回 SystemUI 原有链路,下拉通知栏等系统手势不受影响;
 * - **不转发 MOVE**:避免因歌词视图跟手位移导致命中矩形漂移、手势中断,也不产生跟手动画;
 * - **原生可达时不干预**:歌词视图本就能收到事件时(见 [StatusBarLyric.nativeTouchCount])直接跳过,
 *   避免重复触发。
 *
 * 事件来源(二选一,优先 Hook):
 * 1. Hook 根视图类的 dispatchTouchEvent(仅当该方法由非框架类声明),可观察到每一个事件;
 * 2. 在根视图上挂 OnTouchListener(会保留并转发原有监听器),覆盖"无子视图消费"与"根视图拦截"的情况。
 *
 * @param rootView 状态栏窗口根视图
 * @param targetProvider 提供歌词视图(延迟获取,避免初始化顺序问题)
 */
class LyricTouchRouter(
    private val rootView: ViewGroup,
    private val targetProvider: () -> StatusBarLyric?
) {
    companion object {
        private const val TAG = "LyricTouchRouter"

        /** 命中判定时在歌词矩形外额外容忍的像素 */
        private const val SLOP_DP = 8

        /** 判定为"下拉"的纵向位移阈值倍数:纵向位移超过横向的该倍数时视为下拉,不干预 */
        private const val VERTICAL_DOMINANCE = 1.5f

        /** 纵向位移超过该阈值(像素)才可能判定为下拉 */
        private const val VERTICAL_MIN_DP = 16

        /**
         * 已安装共享 Hook 的类名
         *
         * 状态栏会被多次重新注入,每个控制器都会创建路由。若各自 Hook 同一个
         * dispatchTouchEvent,Hook 会无限叠加,触摸链路越来越长。这里按类名去重:
         * 同一个方法只 Hook 一次,由它统一分发给所有路由。
         */
        private val hookedClassNames = HashSet<String>()

        /** 所有已挂载的路由(共享 Hook 的分发目标) */
        private val activeRouters = CopyOnWriteArrayList<LyricTouchRouter>()

        /** 保护上面两个集合 */
        private val hookLock = Any()

        /**
         * 通道自检间隔：监听通道可能被 SystemUI 重新设置而丢失，需要定期补挂。
         *
         * 自检只在"歌词正在播放且可交互"时运行（见 [shouldKeepHealthCheck]），
         * 停止播放/灭屏后会自行停止，不会常驻空转。
         */
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L

        /**
         * 判定"上一次手势已经卡死"的间隔
         *
         * 通知栏展开等场景会接管触摸流，根视图收不到 UP/CANCEL，
         * [LyricTouchRouter.delivering] 会一直保持 true，导致后续手势错位。
         */
        private const val STALE_GESTURE_MS = 3_000L

        /** 播放恢复 / 亮屏时重新确认所有路由的通道是否还在（应对长时间暂停后触摸失效） */
        fun refreshAllChannels(reason: String) {
            val routers = synchronized(hookLock) { activeRouters.toList() }
            if (routers.isEmpty()) {
                YLog.info(TAG, "refreshAllChannels($reason): no active router")
                return
            }
            routers.forEach {
                runCatching {
                    it.ensureChannelAlive(reason)
                    // 事件触发校正后，若此刻正需要自检（播放中且歌词可交互）则重新拉起循环
                    it.scheduleHealthCheck()
                }
            }
        }

        /** 停止播放 / 灭屏时立即停掉自检，避免后台空转 */
        fun pauseHealthChecks(reason: String) {
            val routers = synchronized(hookLock) { activeRouters.toList() }
            routers.forEach { it.stopHealthCheck(reason) }
        }

        /** 汇总所有路由的状态，供「导出日志」诊断 */
        fun dumpDiagnostics(): String = buildString {
            val routers = synchronized(hookLock) { activeRouters.toList() }
            appendLine("touchRouters: ${routers.size}")
            routers.forEachIndexed { index, router ->
                appendLine("  #$index ${router.dumpState()}")
            }
        }

        /** 丢弃已经脱离窗口的路由，避免长时间运行后堆积并重复转发 */
        private fun pruneStaleRoutersLocked() {
            val iterator = activeRouters.iterator()
            while (iterator.hasNext()) {
                val router = iterator.next()
                val detached = !router.rootView.isAttachedToWindow ||
                        router.targetProvider()?.isAttachedToWindow == false
                if (detached) {
                    YLog.info(TAG, "Prune stale router: ${router.rootView.javaClass.simpleName}")
                    iterator.remove()
                    router.attached = false
                }
            }
        }
    }

    /** 当前生效的观察通道 */
    private enum class Channel { NONE, HOOK, LISTENER, BOTH }

    private var channel: Channel = Channel.NONE

    /** 我们安装的根视图监听包装（自检时用于判断是否被 SystemUI 顶掉） */
    private var wrapperListener: View.OnTouchListener? = null

    /** 最近一次观察到事件的时间（uptimeMillis） */
    private var lastEventAt: Long = 0

    /** 最近一次处理过的事件指纹（双通道会对同一事件各回调一次，需要去重） */
    private var lastEventKey: Long = Long.MIN_VALUE

    /** 通道自检任务 */
    private var healthCheckTask: Runnable? = null

    /** 自检循环是否正在运行 */
    private var healthCheckScheduled = false

    private val density = rootView.resources.displayMetrics.density
    private val slopPx = SLOP_DP * density
    private val verticalMinPx = VERTICAL_MIN_DP * density

    private val location = IntArray(2)

    /** 是否正在向歌词视图转发事件(即本次手势由路由接管) */
    private var delivering = false

    /** 手势起点(屏幕坐标) */
    private var downRawX = 0f
    private var downRawY = 0f

    /** 手势过程中的最大横/纵位移 */
    private var maxDx = 0f
    private var maxDy = 0f

    /** 歌词视图在 DOWN 时刻的位置(屏幕坐标),用于转发时换算坐标 */
    private var targetLeft = 0
    private var targetTop = 0

    /**
     * 歌词视图已收到事件数的基准值
     *
     * 每个事件处理完毕后同步为实际值;若某次事件后实际值超过基准,
     * 说明原生链路也在向歌词视图派发事件,此时路由必须让位。
     */
    private var baseline = 0L

    /** 根视图原有的 OnTouchListener(兜底通道卸载时恢复) */
    private var originalTouchListener: View.OnTouchListener? = null

    /** 是否已挂载(避免同一路由重复注册) */
    private var attached = false

    /**
     * 挂载观察通道
     *
     * 同时挂两条互为备份的通道：Hook 根视图的 dispatchTouchEvent + 根视图
     * OnTouchListener。SystemUI 在运行期可能重新设置根视图的监听器（状态栏重新注入、
     * 展开通知栏等），单通道一旦被顶掉就再也收不到事件，只能重启框架恢复。
     * 两条通道会观察到同一个事件，靠 [lastEventKey] 去重。
     */
    fun attach() {
        if (attached) return
        attached = true
        synchronized(hookLock) {
            pruneStaleRoutersLocked()
            activeRouters.add(this)
        }

        val hooked = tryHookDispatchTouchEvent()
        val listenerInstalled = installRootTouchListener()
        channel = when {
            hooked && listenerInstalled -> Channel.BOTH
            hooked -> Channel.HOOK
            listenerInstalled -> Channel.LISTENER
            else -> Channel.NONE
        }

        if (channel == Channel.NONE) {
            YLog.error(TAG, "No touch channel available! root=${rootView.javaClass.name}")
        } else {
            YLog.info(TAG, "Attached: root=${rootView.javaClass.name}, channel=$channel")
        }

        scheduleHealthCheck()
    }

    /** 卸载(恢复根视图原有监听器;Hook 随进程生命周期,不主动解除) */
    fun detach() {
        attached = false
        delivering = false
        synchronized(hookLock) { activeRouters.remove(this) }
        healthCheckTask?.let { rootView.removeCallbacks(it) }
        healthCheckTask = null
        healthCheckScheduled = false
        wrapperListener = null
        runCatching { rootView.setOnTouchListener(originalTouchListener) }
        originalTouchListener = null
    }

    /**
     * 确认通道仍然可用，不可用则重新挂载
     *
     * 播放恢复、亮屏、自检都会调用：长时间暂停后触摸失效的常见原因是根视图的
     * 监听器被 SystemUI 覆盖，这里把通道补回来。
     */
    private fun ensureChannelAlive(reason: String) {
        if (!attached) return
        if (!rootView.isAttachedToWindow) return

        val healthy = ensureListenerInstalled()
        if (!healthy && channel == Channel.NONE) {
            // 之前两条通道都没挂上，重试一次 Hook
            if (tryHookDispatchTouchEvent()) channel = Channel.HOOK
        }
        YLog.debug(TAG, "ensureChannelAlive($reason): channel=$channel, listener=$healthy")
    }

    /**
     * 确保根视图上的监听包装仍然在位，被覆盖则重新包装
     *
     * @return 监听通道是否可用
     */
    private fun ensureListenerInstalled(): Boolean {
        val current = runCatching { readTouchListener(rootView) }.getOrElse {
            YLog.error(TAG, "Cannot read root touch listener", it)
            return false
        }

        if (current === wrapperListener) return true

        val wrapper = View.OnTouchListener { v, ev ->
            val handled = current?.onTouch(v, ev) ?: false
            observe(ev)
            handled
        }
        originalTouchListener = current
        wrapperListener = wrapper
        rootView.setOnTouchListener(wrapper)

        if (current != null) {
            YLog.info(
                TAG,
                "Root touch listener replaced by ${current.javaClass.name}, re-wrapped"
            )
        }
        channel = if (channel == Channel.HOOK) Channel.BOTH else Channel.LISTENER
        return true
    }

    /**
     * 周期自检：监听通道被顶掉时自动补挂。
     *
     * 只在歌词正在播放且可交互时运行；不满足条件就不排任何任务，
     * 由 [refreshAllChannels]（亮屏 / 恢复播放）在需要时重新拉起。
     */
    private fun scheduleHealthCheck() {
        if (!attached || healthCheckScheduled) return
        if (!shouldKeepHealthCheck()) return

        healthCheckScheduled = true
        val task = object : Runnable {
            override fun run() {
                if (!attached) {
                    healthCheckScheduled = false
                    return
                }
                if (!shouldKeepHealthCheck()) {
                    healthCheckScheduled = false
                    YLog.debug(TAG, "Health check paused: lyric not interactive")
                    return
                }
                runCatching { ensureChannelAlive("health-check") }
                    .onFailure { YLog.error(TAG, "Health check failed", it) }
                rootView.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
        healthCheckTask = task
        rootView.postDelayed(task, HEALTH_CHECK_INTERVAL_MS)
    }

    /** 停止自检并移除已排期的任务 */
    private fun stopHealthCheck(reason: String) {
        if (!healthCheckScheduled) return
        healthCheckScheduled = false
        healthCheckTask?.let { rootView.removeCallbacks(it) }
        healthCheckTask = null
        YLog.debug(TAG, "Health check stopped: $reason")
    }

    /**
     * 是否值得继续自检
     *
     * 只有"正在播放、屏幕亮着、歌词视图可交互"时触摸才有意义，
     * 其余时间不排任何定时任务，避免后台空转唤醒主线程。
     */
    private fun shouldKeepHealthCheck(): Boolean {
        if (!LyricViewController.isPlaying) return false
        if (ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF) return false
        val target = runCatching { targetProvider() }.getOrNull() ?: return false
        return isTargetActive(target)
    }

    /** 输出本路由的状态，供「导出日志」诊断 */
    fun dumpState(): String = buildString {
        append("channel=$channel, attached=$attached, delivering=$delivering")
        append(", rootAttached=${rootView.isAttachedToWindow}")
        append(", eventAgo=${SystemClock.uptimeMillis() - lastEventAt}ms")
        append(", baseline=$baseline")
        val target = runCatching { targetProvider() }.getOrNull()
        if (target == null) {
            append(", target=null")
        } else {
            append(
                ", target[attached=${target.isAttachedToWindow}, visible=${target.isVisible}, " +
                        "size=${target.width}x${target.height}, native=${target.nativeTouchCount}]"
            )
        }
    }

    // --- 事件观察与转发 ---

    /**
     * 观察一次触摸事件。
     *
     * 事件本身始终交回 SystemUI 原有链路(本类从不消费事件),这里只把命中歌词区域的
     * 手势**镜像转发**给歌词视图:DOWN / MOVE / UP 原样转发,因此单击、长按、横滑
     * 的判定与视觉反馈都与原生一致;一旦判定为纵向下拉,立即补发 CANCEL 并让位,
     * 保证下拉通知栏不受影响。
     */
    private fun observe(ev: MotionEvent) {
        // 双通道会对同一个事件各回调一次(Hook 在派发后、监听器在派发中),这里去重
        val eventKey = ev.eventTime * 16 + ev.actionMasked
        if (eventKey == lastEventKey && ev.actionMasked != MotionEvent.ACTION_MOVE) {
            return
        }
        lastEventKey = eventKey

        val now = SystemClock.uptimeMillis()

        // 上一次手势没收到 UP/CANCEL(通知栏展开会接管触摸流):先复位,避免状态错乱
        if (delivering &&
            (ev.actionMasked == MotionEvent.ACTION_DOWN || now - lastEventAt > STALE_GESTURE_MS)
        ) {
            YLog.info(TAG, "Reset stuck delivering state (stale=${now - lastEventAt}ms)")
            cancelDelivering(targetProvider())
        }
        lastEventAt = now

        val target = targetProvider()

        if (target == null || !isTargetActive(target)) {
            cancelDelivering(target)
            return
        }

        // 原生链路本次已把事件送到歌词视图:路由让位,避免重复触发
        if (target.nativeTouchCount > baseline) {
            if (delivering) {
                YLog.info(TAG, "Native delivery detected, yield")
                cancelDelivering(target)
            }
            baseline = target.nativeTouchCount
            return
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInsideTarget(ev, target)) {
                    baseline = target.nativeTouchCount
                    return
                }

                target.getLocationOnScreen(location)
                targetLeft = location[0]
                targetTop = location[1]
                downRawX = ev.rawX
                downRawY = ev.rawY
                maxDx = 0f
                maxDy = 0f

                delivering = true
                forward(target, ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!delivering) {
                    baseline = target.nativeTouchCount
                    return
                }

                maxDx = max(maxDx, abs(ev.rawX - downRawX))
                maxDy = max(maxDy, abs(ev.rawY - downRawY))

                // 纵向为主:判定为下拉通知栏,把手势交回系统
                if (isPullDown()) {
                    YLog.info(TAG, "Vertical drag detected, yield to notification panel")
                    cancelDelivering(target)
                } else {
                    forward(target, ev)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!delivering) {
                    baseline = target.nativeTouchCount
                    return
                }
                forward(target, ev)
                delivering = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (delivering) forward(target, ev)
                delivering = false
            }
        }

        baseline = target.nativeTouchCount
    }

    /** 是否判定为纵向下拉手势 */
    private fun isPullDown(): Boolean =
        maxDy > verticalMinPx && maxDy > maxDx * VERTICAL_DOMINANCE

    /** 结束接管:向歌词视图补发 CANCEL,使其复位 */
    private fun cancelDelivering(target: StatusBarLyric?) {
        if (!delivering || target == null) {
            delivering = false
            return
        }
        delivering = false
        forward(target, MotionEvent.ACTION_CANCEL)
    }

    /**
     * 把事件换算到歌词视图坐标系后转发。
     *
     * @param action 为 null 时沿用原事件的动作
     */
    private fun forward(target: StatusBarLyric, ev: MotionEvent) {
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation((ev.rawX - targetLeft) - ev.x, (ev.rawY - targetTop) - ev.y)
        try {
            target.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    private fun forward(target: StatusBarLyric, action: Int) {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, 0f, 0f, 0)
        try {
            target.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    /** 歌词视图是否处于可交互状态 */
    private fun isTargetActive(target: StatusBarLyric): Boolean =
        target.isAttachedToWindow && target.isVisible && target.width > 0 && target.height > 0

    /** 事件是否落在歌词(或隐藏态把手)区域内 */
    private fun isInsideTarget(ev: MotionEvent, target: StatusBarLyric): Boolean {
        target.getLocationOnScreen(location)

        val left = location[0] - slopPx
        val top = location[1] - slopPx
        val right = location[0] + target.width + slopPx
        val bottom = location[1] + target.height + slopPx

        return ev.rawX >= left && ev.rawX <= right && ev.rawY >= top && ev.rawY <= bottom
    }

    // --- 观察通道 ---

    /**
     * 尝试 Hook 根视图类的 dispatchTouchEvent。
     *
     * 仅当该方法由 SystemUI(非框架)类声明时才 Hook,避免影响
     * android.view.ViewGroup 等框架类的全局调用。
     *
     * @return 是否 Hook 成功
     */
    private fun tryHookDispatchTouchEvent(): Boolean {
        val module = runCatching { ModuleEntry.instance }.getOrNull() ?: return false

        var current: Class<*> = rootView.javaClass
        while (true) {
            val owner = current
            val method = runCatching {
                owner.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            }.getOrNull()

            if (method != null) {
                if (isFrameworkClass(owner)) {
                    YLog.info(
                        TAG,
                        "dispatchTouchEvent declared in framework class ${owner.name}, skip hook"
                    )
                    return false
                }

                val className = owner.name
                val alreadyHooked = synchronized(hookLock) {
                    hookedClassNames.contains(className)
                }
                if (alreadyHooked) {
                    // 该方法上已有共享 Hook,它会按根视图身份分发给所有路由
                    YLog.info(TAG, "Reusing shared hook on $className.dispatchTouchEvent")
                    return true
                }

                return runCatching {
                    synchronized(hookLock) { hookedClassNames.add(className) }

                    module.hook(method).intercept { chain ->
                        val ev = chain.args.firstOrNull() as? MotionEvent
                        // 先让 SystemUI 正常派发(因此下拉通知栏等系统手势不受影响),
                        // 再判断歌词视图是否收到了事件,没有则由路由转发
                        val result = chain.proceed()

                        if (ev != null) {
                            val receiver = chain.thisObject
                            for (router in activeRouters) {
                                if (router.rootView === receiver) {
                                    runCatching { router.observe(ev) }
                                        .onFailure { YLog.error(TAG, "Observe failed", it) }
                                }
                            }
                        }
                        result
                    }
                    YLog.info(TAG, "Hooked $className.dispatchTouchEvent")
                    true
                }.getOrElse {
                    synchronized(hookLock) { hookedClassNames.remove(className) }
                    YLog.error(TAG, "Failed to hook dispatchTouchEvent", it)
                    false
                }
            }

            current = current.superclass ?: return false
        }

        return false
    }

    /**
     * 在根视图上挂 OnTouchListener 作为兜底观察通道。
     *
     * 该监听器会在"无子视图消费事件"或"根视图自身拦截事件"时被调用,
     * 且始终返回原监听器的结果,不改变 SystemUI 的触摸行为。
     * 若无法读取原有监听器则放弃挂载,避免覆盖系统监听器。
     */
    private fun installRootTouchListener(): Boolean {
        val installed = runCatching { ensureListenerInstalled() }.getOrElse {
            YLog.error(TAG, "Cannot install root touch listener", it)
            false
        }
        if (installed) {
            YLog.info(TAG, "Root touch listener installed: root=${rootView.javaClass.name}")
        }
        return installed
    }

    /** 反射读取视图上已注册的 OnTouchListener(用于链式保留) */
    private fun readTouchListener(view: View): View.OnTouchListener? {
        val listenerInfoField = View::class.java
            .getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }
        val listenerInfo = listenerInfoField.get(view) ?: return null

        val touchListenerField = Class.forName("android.view.View\$ListenerInfo")
            .getDeclaredField("mOnTouchListener")
            .apply { isAccessible = true }

        return touchListenerField.get(listenerInfo) as? View.OnTouchListener
    }

    private fun isFrameworkClass(clazz: Class<*>): Boolean {
        val name = clazz.name
        return name.startsWith("android.")
                || name.startsWith("androidx.")
                || name.startsWith("com.android.internal.")
                || name.startsWith("java.")
                || name.startsWith("kotlin.")
    }
}
