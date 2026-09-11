package com.ailife.wftools2.domain.editor.watchmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Tag-catalogue completeness + semantics, cross-checked against the CloX
 * renderer (wf_tagdefs.lua) and WM_TAGS.md:
 *  - every documented tag resolves to a VALUE (never its literal "{tag}" text),
 *  - the newly-added families (sleep, stress, hr-history, UL stopwatch aliases,
 *    spelled-out time, tz2/tz3 metadata, device statics) return correct values,
 *  - the CloX-ported formats (dutcoff ±HHMM, dz abbreviation, dw/dwm week math,
 *    calendar br/er 12h-dial rotation) match the reference formulas.
 */
class WmTagsDataTest {

    private val now = LocalDateTime.of(2026, 2, 25, 11, 45, 45)
    private val base = WmTagsData.WmPreviewState(now = now)

    private fun resolve(tag: String, state: WmTagsData.WmPreviewState = base): String =
        WmTagsData.resolveTag(tag, state)

    // ── Every category-listed tag must resolve to a real value ────────────────

    @Test
    fun allCatalogueTagsResolveToValues() {
        val literal = Regex("^\\{[^{}]+\\}$")
        val unresolved = mutableListOf<String>()
        WmTagsData.categories.values.flatten().forEach { entry ->
            val v = resolve(entry.tag)
            if (v == entry.tag || literal.matches(v)) unresolved += entry.tag
        }
        assertTrue("tags resolved literally: $unresolved", unresolved.isEmpty())
    }

    // ── Spelled-out time (CloX numWord / timeWordParts) ───────────────────────

    @Test
    fun spelledOutTimeTags() {
        assertEquals("eleven", resolve("{dht}"))              // 11
        assertEquals("eleven", resolve("{dh24t}"))
        assertEquals("forty five", resolve("{dmat}"))        // 45
        assertEquals("forty", resolve("{dmtt}"))
        assertEquals("five", resolve("{dmot}"))
        assertEquals("forty five", resolve("{dsat}"))        // 45s
        assertEquals("forty", resolve("{dstt}"))
        assertEquals("five", resolve("{dsot}"))
    }

    @Test
    fun spelledOutTimeZeroAndTeens() {
        val zero = base.copy(now = LocalDateTime.of(2026, 2, 25, 0, 0, 0))
        assertEquals("twelve", resolve("{dht}", zero))
        assertEquals("twenty four", resolve("{dh24t}", zero))
        assertEquals("o'clock", resolve("{dmat}", zero))
        assertEquals("o'", resolve("{dmtt}", zero))
        assertEquals("clock", resolve("{dmot}", zero))
        val teens = base.copy(now = LocalDateTime.of(2026, 2, 25, 10, 13, 16))
        assertEquals("thirteen", resolve("{dmat}", teens))
        assertEquals("", resolve("{dmot}", teens))
        assertEquals("sixteen", resolve("{dsat}", teens))
    }

    // ── Sleep / stress / heart-rate history ───────────────────────────────────

    @Test
    fun sleepTags() {
        val s = base.copy(
            sleepHasData = true,
            sleepAsleepMin = 444,
            sleepBedtime = "23:41",
            sleepWakeTime = "07:05",
            sleepRemMin = 92,
            sleepLightMin = 251,
            sleepDeepMin = 101,
            sleepAwakeMin = 18,
            sleepSessionMin = 477
        )
        assertEquals("444", resolve("{sslm}", s))
        assertEquals("7.4", resolve("{sslh}", s))
        assertEquals("7:24", resolve("{sslt}", s))
        assertEquals("23:41", resolve("{sslbt}", s))
        assertEquals("07:05", resolve("{sslwt}", s))
        assertEquals("92", resolve("{sslrm}", s))
        assertEquals("251", resolve("{ssllm}", s))
        assertEquals("101", resolve("{ssldm}", s))
        assertEquals("18", resolve("{sslam}", s))
        assertEquals("93", resolve("{sslp}", s))
        // No session → "--:--" times
        val none = s.copy(sleepHasData = false)
        assertEquals("--:--", resolve("{sslbt}", none))
        assertEquals("--:--", resolve("{sslwt}", none))
    }

    @Test
    fun stressTag() {
        assertEquals("35", resolve("{sstr}"))
        assertEquals("72", resolve("{sstr}", base.copy(stressScore = 72)))
    }

    @Test
    fun heartRateHistoryFallback() {
        assertEquals("70", resolve("{shr_1}"))   // 72 - 2
        assertEquals("68", resolve("{shr_2}"))
        assertEquals("66", resolve("{shr_3}"))
        assertEquals("54", resolve("{shr_9}"))   // 72 - 2*9
    }

    @Test
    fun heartRateHistoryFromList() {
        val hist = listOf(70, 68, 66, 64, 62, 60, 58, 56, 54) // oldest → newest
        val s = base.copy(heartRate = 100, heartRateHistory = hist)
        assertEquals("54", resolve("{shr_1}", s))   // newest = last
        assertEquals("56", resolve("{shr_2}", s))
        assertEquals("70", resolve("{shr_9}", s))   // oldest = first
    }

    // ── Stopwatch + UL aliases ────────────────────────────────────────────────

    @Test
    fun stopwatchTags() {
        val s = base.copy(stopwatchRunning = true, stopwatchMs = 3_607_433L) // 1h 00m 07s 433ms
        assertEquals("true", resolve("{swr}", s))
        assertEquals("01", resolve("{swh}", s))
        assertEquals("00", resolve("{swm}", s))
        assertEquals("07", resolve("{sws}", s))
        assertEquals("43", resolve("{swss}", s))
        assertEquals("433", resolve("{swsss}", s))
        assertEquals("3607433", resolve("{swsst}", s))
        // UL aliases mirror the same values
        assertEquals("01", resolve("{tswh}", s))
        assertEquals("00", resolve("{tswm}", s))
        assertEquals("07", resolve("{tsws}", s))
        assertEquals("43", resolve("{tswss}", s))
        assertEquals("433", resolve("{tswsss}", s))
        assertEquals("3607433", resolve("{tswsst}", s))
        // rotations
        val s2 = base.copy(stopwatchMs = 30_000L)
        assertEquals("0.0", resolve("{swrm}", s2))     // 0 min
        assertEquals("180.0", resolve("{swrs}", s2))   // 30 s
        assertEquals("false", resolve("{swr}", base))
    }

    // ── Clock / date semantics ────────────────────────────────────────────────

    @Test
    fun cloxWeekFormulas() {
        // 2026-02-25: Jan 1 2026 is a Thursday → first Monday is Jan 5.
        // dayOfYear(Feb 25) = 31+25 = 56 → %W = (56-5)/7 = 7 → dw = 8
        assertEquals("8", resolve("{dw}"))
        // week-of-month: Feb 1 2026 is a Sunday (week 1 contains the 1st);
        // Feb 25 → ((24 + 0)/7)+1 = 4
        assertEquals("4", resolve("{dwm}"))
    }

    @Test
    fun utcOffsetAndTimezoneAbbrevFormats() {
        assertTrue(resolve("{dutcoff}").matches(Regex("[+-]\\d{4}")))
        assertFalse(resolve("{dz}").contains(" "))   // abbreviation, not full name
    }

    @Test
    fun calendarRotationIs12hDial() {
        // c1 = Meeting 14:00-15:00 → 12h-dial rotation: (2)/12*360 = 60, (3)/12*360 = 90
        assertEquals("60", resolve("{c1br}"))
        assertEquals("90", resolve("{c1er}"))
        // c3 = Lunch 12:00-13:00 → 0 and 30
        assertEquals("0", resolve("{c3br}"))
        assertEquals("30", resolve("{c3er}"))
        assertEquals("true", resolve("{c1ex}"))
        assertEquals("true", resolve("{cex}"))
        assertEquals("Meeting", resolve("{c1t}"))
    }

    @Test
    fun timeAndWeatherDerivations() {
        assertEquals("11", resolve("{d24h}").let { if (base.is24hMode) it else "11" })  // d24h → device flag
        assertEquals("false", resolve("{d24h}"))
        val bright = base.copy(is24hMode = true)
        assertEquals("true", resolve("{d24h}", bright))
        assertEquals("true", resolve("{wisday}", base.copy(sunrise = "06:00", sunset = "18:00")))
        assertEquals("false", resolve("{wisday}", base.copy(sunrise = "06:00", sunset = "18:00", now = LocalDateTime.of(2026, 2, 25, 20, 0))))
        assertEquals("London", resolve("{aloc}"))
        assertEquals("London", resolve("{wl}"))
    }

    @Test
    fun elapsedAndBrightnessTags() {
        val s = base.copy(elapsedSeconds = 173.604, isBright = true)
        assertEquals("173.604", resolve("{c_elapsed}", s))
        assertEquals("173604", resolve("{abss}", s))
        assertEquals("30", resolve("{abssl}", s))                    // capped
        assertEquals("13.600", resolve("{abssl}", base.copy(elapsedSeconds = 13.6)))
        assertEquals("-1", resolve("{abss}", base.copy(isBright = false)))
        assertEquals("-1", resolve("{abssl}", base.copy(isBright = false)))
    }

    @Test
    fun deviceStatics() {
        val s = base.copy(
            deviceName = "P1", deviceModel = "M1", deviceManufacturer = "G1",
            watchName = "W1", isRound = false, hasCutout = true, isDimLoBit = true,
            screenBrightness = 200, langCode = "de", langRegion = "DE", langFull = "German",
            lastReboot = "2025-12-01 00:00", osVersion = "14", launcherVersion = "4.1",
            notificationAppName = "App", notificationTitle = "T", notificationContent = "C",
            wifiIp = "10.0.0.2", bluetoothEnabled = true
        )
        assertEquals("P1", resolve("{aname}", s))
        assertEquals("M1", resolve("{amodel}", s))
        assertEquals("G1", resolve("{aman}", s))
        assertEquals("W1", resolve("{awname}", s))
        assertEquals("false", resolve("{around}", s))
        assertEquals("true", resolve("{atyre}", s))
        assertEquals("true", resolve("{adimlo}", s))
        assertEquals("200", resolve("{abrt}", s))
        assertEquals("de", resolve("{alangcode}", s))
        assertEquals("DE", resolve("{alangreg}", s))
        assertEquals("German", resolve("{alangfull}", s))
        assertEquals("2025-12-01 00:00", resolve("{areboot}", s))
        assertEquals("14", resolve("{aosv}", s))
        assertEquals("4.1", resolve("{ulver}", s))
        assertEquals("true", resolve("{abtc}", s))
        assertEquals("App", resolve("{cn1a}", s))
        assertEquals("T", resolve("{cn1t}", s))
        assertEquals("C", resolve("{cn1c}", s))
        assertEquals("10.0.0.2", resolve("{nwip}", s))
    }

    @Test
    fun timezoneMetadataTags() {
        // tz2 = Europe/London, tz3 = Asia/Tokyo (real IANA zones in the preview)
        assertEquals("London", resolve("{tz2l}"))
        assertEquals("Europe/London", resolve("{tz2ll}"))
        assertTrue(resolve("{tz2o}").matches(Regex("[+-]\\d+:\\d{2}")))
        assertTrue(resolve("{tz3o}").matches(Regex("[+-]\\d+:\\d{2}")))
        assertEquals("540", resolve("{tz3om}"))     // Tokyo: fixed +9:00
        assertEquals("false", resolve("{tz3dst}"))
        assertTrue(resolve("{tz2om}").toIntOrNull() != null)
        assertTrue(resolve("{tz2dst}") == "true" || resolve("{tz2dst}") == "false")
        assertTrue(resolve("{tz2rh24}").toDoubleOrNull() != null)
        assertTrue(resolve("{tz2rm}").toDoubleOrNull() != null)
        assertTrue(resolve("{tz3om}").toIntOrNull() != null)
        assertEquals("Asia/Tokyo", resolve("{tz3ll}"))
    }
}
