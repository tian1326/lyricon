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
    }

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

    /** 挂载观察通道(Hook 优先,失败则退化为根视图监听器) */
    fun attach() {
        if (attached) return
        attached = true
        synchronized(hookLock) { activeRouters.add(this) }

        if (tryHookDispatchTouchEvent()) {
            YLog.info(TAG, "Attached via hook: root=${rootView.javaClass.name}")
        } else {
            installRootTouchListener()
        }
    }

    /** 卸载(恢复根视图原有监听器;Hook 随进程生命周期,不主动解除) */
    fun detach() {
        attached = false
        delivering = false
        synchronized(hookLock) { activeRouters.remove(this) }
        runCatching { rootView.setOnTouchListener(originalTouchListener) }
        originalTouchListener = null
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
    private fun installRootTouchListener() {
        val existing = runCatching { readTouchListener(rootView) }.getOrElse {
            YLog.error(TAG, "Cannot read existing touch listener, skip root listener", it)
            return
        }

        originalTouchListener = existing
        rootView.setOnTouchListener { v, ev ->
            val handled = existing?.onTouch(v, ev) ?: false
            observe(ev)
            handled
        }

        YLog.info(TAG, "Attached via root touch listener: root=${rootView.javaClass.name}")
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
