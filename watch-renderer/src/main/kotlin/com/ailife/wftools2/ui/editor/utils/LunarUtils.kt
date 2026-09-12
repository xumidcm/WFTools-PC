package com.ailife.wftools2.ui.editor.utils

import java.time.LocalDate

/**
 * Real Chinese lunar calendar + 24 solar terms.
 *
 * Uses the canonical 1900–2100 `lunarInfo` table (bit-packed month lengths + leap month) with the
 * standard conversion walk, and the 寿星通用公式 for solar terms (accurate to ~±1 day for
 * 2001–2100). This is the SAME algorithm the HSC→WM converter emits as Lua
 * (`HscToWmConverterLunar`), so the editor preview matches the converted watchface.
 */
object LunarUtils {

    private val lunarInfo = intArrayOf(
        0x04bd8,0x04ae0,0x0a570,0x054d5,0x0d260,0x0d950,0x16554,0x056a0,0x09ad0,0x055d2,
        0x04ae0,0x0a5b6,0x0a4d0,0x0d250,0x1d255,0x0b540,0x0d6a0,0x0ada2,0x095b0,0x14977,
        0x04970,0x0a4b0,0x0b4b5,0x06a50,0x06d40,0x1ab54,0x02b60,0x09570,0x052f2,0x04970,
        0x06566,0x0d4a0,0x0ea50,0x06e95,0x05ad0,0x02b60,0x186e3,0x092e0,0x1c8d7,0x0c950,
        0x0d4a0,0x1d8a6,0x0b550,0x056a0,0x1a5b4,0x025d0,0x092d0,0x0d2b2,0x0a950,0x0b557,
        0x06ca0,0x0b550,0x15355,0x04da0,0x0a5b0,0x14573,0x052b0,0x0a9a8,0x0e950,0x06aa0,
        0x0aea6,0x0ab50,0x04b60,0x0aae4,0x0a570,0x05260,0x0f263,0x0d950,0x05b57,0x056a0,
        0x096d0,0x04dd5,0x04ad0,0x0a4d0,0x0d4d4,0x0d250,0x0d558,0x0b540,0x0b6a0,0x195a6,
        0x095b0,0x049b0,0x0a974,0x0a4b0,0x0b27a,0x06a50,0x06d40,0x0af46,0x0ab60,0x09570,
        0x04af5,0x04970,0x064b0,0x074a3,0x0ea50,0x06b58,0x055c0,0x0ab60,0x096d5,0x092e0,
        0x0c960,0x0d954,0x0d4a0,0x0da50,0x07552,0x056a0,0x0abb7,0x025d0,0x092d0,0x0cab5,
        0x0a950,0x0b4a0,0x0baa4,0x0ad50,0x055d9,0x04ba0,0x0a5b0,0x15176,0x052b0,0x0a930,
        0x07954,0x06aa0,0x0ad50,0x05b52,0x04b60,0x0a6e6,0x0a4e0,0x0d260,0x0ea65,0x0d530,
        0x05aa0,0x076a3,0x096d0,0x04afb,0x04ad0,0x0a4d0,0x1d0b6,0x0d250,0x0d520,0x0dd45,
        0x0b5a0,0x056d0,0x055b2,0x049b0,0x0a577,0x0a4b0,0x0aa50,0x1b255,0x06d20,0x0ada0,
        0x14b63,0x09370,0x049f8,0x04970,0x064b0,0x168a6,0x0ea50,0x06b20,0x1a6c4,0x0aae0,
        0x0a2e0,0x0d2e3,0x0c960,0x0d557,0x0d4a0,0x0da50,0x05d55,0x056a0,0x0a6d0,0x055d4,
        0x052d0,0x0a9b8,0x0a950,0x0b4a0,0x0b6a6,0x0ad50,0x055a0,0x0aba4,0x0a5b0,0x052b0,
        0x0b273,0x06930,0x07337,0x06aa0,0x0ad50,0x14b55,0x04b60,0x0a570,0x054e4,0x0d160,
        0x0e968,0x0d520,0x0daa0,0x16aa6,0x056d0,0x04ae0,0x0a9d4,0x0a2d0,0x0d150,0x0f252,
        0x0d520
    )

    private fun info(y: Int): Int = lunarInfo.getOrElse(y - 1900) { 0 }
    private fun leapMonth(y: Int): Int = info(y) and 0xf
    private fun leapDays(y: Int): Int =
        if (leapMonth(y) != 0) (if (info(y) and 0x10000 != 0) 30 else 29) else 0
    private fun monthDays(y: Int, m: Int): Int =
        if (info(y) and (0x10000 shr m) != 0) 30 else 29
    private fun yearDays(y: Int): Int {
        var sum = 348
        var i = 0x8000
        while (i > 0x8) {
            if (info(y) and i != 0) sum++
            i = i shr 1
        }
        return sum + leapDays(y)
    }

    private val monthNames = arrayOf(
        "正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月"
    )
    private val dayNames = arrayOf(
        "初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
        "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
        "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"
    )

    // (lunarMonth 1-12, lunarDay 1-30, isLeapMonth)
    private data class Lunar(val month: Int, val day: Int, val isLeap: Boolean)

    private fun toLunar(date: LocalDate): Lunar {
        // Days since 1900-01-31 (== lunar 1900-01-01), via java.time (exact).
        var offset = (date.toEpochDay() - LocalDate.of(1900, 1, 31).toEpochDay()).toInt()
        var temp = 0
        var i = 1900
        while (i < 2101 && offset > 0) {
            temp = yearDays(i); offset -= temp; i++
        }
        if (offset < 0) { offset += temp; i-- }
        val year = i
        val leap = leapMonth(year)
        var isLeap = false
        i = 1
        while (i < 13 && offset > 0) {
            if (leap > 0 && i == (leap + 1) && !isLeap) {
                i--; isLeap = true; temp = leapDays(year)
            } else {
                temp = monthDays(year, i)
            }
            if (isLeap && i == (leap + 1)) isLeap = false
            offset -= temp; i++
        }
        if (offset == 0 && leap > 0 && i == leap + 1) {
            if (isLeap) isLeap = false else { isLeap = true; i-- }
        }
        if (offset < 0) { offset += temp; i-- }
        return Lunar(i, offset + 1, isLeap)
    }

    fun getLunarMonth(date: LocalDate): String {
        val l = toLunar(date)
        val s = monthNames.getOrElse(l.month - 1) { "正月" }
        return if (l.isLeap) "闰$s" else s
    }

    fun getLunarDay(date: LocalDate): String {
        val l = toLunar(date)
        return dayNames.getOrElse(l.day - 1) { "初一" }
    }

    // 24 solar terms via 寿星通用公式 (21st-century constants).
    private val termC = doubleArrayOf(
        5.4055,20.12,3.87,18.73,5.63,20.646,4.81,20.1,5.52,21.04,5.678,21.37,
        7.108,22.83,7.5,23.13,7.646,23.042,8.318,23.438,7.438,22.36,7.18,21.94
    )
    private val termNames = arrayOf(
        "小寒","大寒","立春","雨水","惊蛰","春分","清明","谷雨","立夏","小满","芒种","夏至",
        "小暑","大暑","立秋","处暑","白露","秋分","寒露","霜降","立冬","小雪","大雪","冬至"
    )

    private fun termDay(year: Int, n: Int): Int {
        val y = year - 2000
        return Math.floor(y * 0.2422 + termC[n - 1]).toInt() - Math.floorDiv(y, 4)
    }

    /** Solar term period the date falls in (the most recent term on/before it). */
    fun getSolarTerm(date: LocalDate): String {
        val m = date.monthValue
        val d = date.dayOfMonth
        val n2 = 2 * m
        val n1 = n2 - 1
        val idx = when {
            d >= termDay(date.year, n2) -> n2
            d >= termDay(date.year, n1) -> n1
            else -> (n2 - 2).let { if (it < 1) 24 else it }
        }
        return termNames.getOrElse(idx - 1) { "小寒" }
    }
}
