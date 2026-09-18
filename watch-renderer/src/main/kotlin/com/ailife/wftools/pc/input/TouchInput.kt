package com.ailife.wftools.pc.input

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerType

/**
 * Windows 触屏适配的共享判据。
 *
 * 桌面端的键盘鼠标假设散布在画布手势与列表交互里：只有指针悬停才显示操作按钮、
 * 右键呼出上下文菜单、滚轮负责缩放。触摸屏没有悬停、没有右键、也没有滚轮，因此这些
 * 入口在触屏上要么不可达，要么命中区过小。这里的常量与纯函数是各处适配的共同来源。
 */
object TouchTargets {
    /** 触摸命中的建议最小边长（dp）。 */
    const val MIN_TOUCH_DP = 44f

    /** 分隔条宽度：鼠标沿用紧凑布局，触屏加宽以便手指抓取。 */
    const val SPLITTER_MOUSE_DP = 12f
    const val SPLITTER_TOUCH_DP = 28f

    /** 长按判定：按住达到该时长且位移未超过容差。 */
    const val LONG_PRESS_MILLIS = 500L
}

/** 事件里是否含触屏指针。鼠标、笔与触屏由 Compose 分别上报。 */
fun PointerEvent.isFromTouch(): Boolean = changes.any { it.type == PointerType.Touch }

/** 事件是否完全来自触屏；用于只在纯触摸手势下才启用的行为。 */
fun PointerEvent.isTouchOnly(): Boolean = changes.isNotEmpty() && changes.all { it.type == PointerType.Touch }

/** 触屏下把命中尺寸抬到可点按的下限，鼠标保持原有紧凑尺寸。 */
fun touchTargetDp(declaredDp: Float, touch: Boolean): Float =
    if (touch) maxOf(declaredDp, TouchTargets.MIN_TOUCH_DP) else declaredDp

/** 分隔条宽度：触屏更宽，便于手指抓取。 */
fun splitterThicknessDp(touch: Boolean): Float =
    if (touch) TouchTargets.SPLITTER_TOUCH_DP else TouchTargets.SPLITTER_MOUSE_DP

/**
 * 长按判定。触摸屏没有右键，长按是呼出上下文菜单的唯一入口。
 *
 * @param elapsedMillis 按住时长
 * @param movementPx 累计位移（像素）
 * @param movementSlopPx 允许的位移容差（像素）
 */
fun isLongPress(elapsedMillis: Long, movementPx: Float, movementSlopPx: Float): Boolean =
    elapsedMillis >= TouchTargets.LONG_PRESS_MILLIS && movementPx <= movementSlopPx
