package com.ailife.wftools.pc.input

import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

/** Contracts for the shared Windows touch-input judgements. */
class TouchInputTest {
    @Test fun `touch targets never shrink below the comfortable minimum`() {
        assertEquals(TouchTargets.MIN_TOUCH_DP, touchTargetDp(28f, touch = true))
        assertEquals(TouchTargets.MIN_TOUCH_DP, touchTargetDp(0f, touch = true))
        // An already generous control keeps its size rather than being pulled down.
        assertEquals(56f, touchTargetDp(56f, touch = true))
    }

    @Test fun `mouse keeps the compact declared size`() {
        assertEquals(12f, touchTargetDp(12f, touch = false))
        assertEquals(28f, touchTargetDp(28f, touch = false))
    }

    @Test fun `the splitter is wider for touch and unchanged for the mouse`() {
        assertEquals(TouchTargets.SPLITTER_MOUSE_DP, splitterThicknessDp(touch = false))
        assertEquals(TouchTargets.SPLITTER_TOUCH_DP, splitterThicknessDp(touch = true))
        assertTrue("触屏分隔条必须比鼠标宽", splitterThicknessDp(true) > splitterThicknessDp(false))
    }

    @Test fun `a long press requires both duration and stillness`() {
        assertTrue(isLongPress(TouchTargets.LONG_PRESS_MILLIS, 0f, 12f))
        assertTrue(isLongPress(TouchTargets.LONG_PRESS_MILLIS + 500, 12f, 12f))
        assertFalse("时间不够不能算长按", isLongPress(TouchTargets.LONG_PRESS_MILLIS - 1, 0f, 12f))
        assertFalse("移动过多说明是拖动，不是长按", isLongPress(TouchTargets.LONG_PRESS_MILLIS + 500, 13f, 12f))
    }

    @Test fun `the long press threshold is a usable touch duration`() {
        assertTrue("长按阈值应适合手指操作", TouchTargets.LONG_PRESS_MILLIS in 300L..800L)
        assertTrue("触控目标应达到无障碍建议的下限", TouchTargets.MIN_TOUCH_DP >= 44f)
    }

    @Test fun `pointer types are distinguishable`() {
        assertEquals(PointerType.Touch, PointerType.Touch)
        assertNotEquals(PointerType.Touch, PointerType.Mouse)
        assertNotEquals(PointerType.Touch, PointerType.Stylus)
    }
}
