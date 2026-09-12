package com.ailife.wftools2.domain.editor.watchmaker




object WmTagsData {

    data class TagEntry(val tag: String, val descriptionKey: String, val sample: String)

    // Top-level data points for the preview. Every derived tag (percentages,
    // rotations, with-unit / zero-padded variants) computes from THESE fields —
    // nothing downstream should hardcode a base value or a goal. To add user
    // control over a value later, expose the matching field here; all dependent
    // tags update automatically.
    data class WmPreviewState(
        // Optional map of pre-resolved tag values ("{dh}" -> "3"). When set (editor preview),
        // resolveTag returns the map value directly instead of computing from [now]/sensors.
        // Reading it from a Compose SnapshotStateMap during composition is what makes tag
        // updates propagate to exactly the layers that reference each tag. Null for
        // flatten/export callers, which resolve everything from the fields below as before.
        val tagOverride: Map<String, String>? = null,
        val now: java.time.LocalDateTime = java.time.LocalDateTime.now(),

        // ── Battery (watch) ──
        val batteryPercentage: Int = 75,
        val batteryCharging: Boolean = false,
        val batteryVoltage: Float = 3.805f,
        val batteryCurrentMa: Int = 350,
        val batteryTempC: Int = 36,
        // ── Battery (phone) ──
        val phoneBatteryPercentage: Int = 82,
        val phoneCharging: Boolean = true,
        val phoneBatteryTempC: Int = 34,

        // ── Health / Activity: base value + its goal ──
        val steps: Int = 8500,
        val stepGoal: Int = 10000,
        val heartRate: Int = 72,
        val maxHeartRate: Int = 180,
        val calories: Int = 420,
        val calorieGoal: Int = 700,
        val distanceKm: Float = 6.2f,
        val distanceGoalKm: Float = 10f,
        val distanceUnit: String = "KM",
        val bloodOxygen: Int = 98,
        val flightsClimbed: Int = 8,
        val foodCalories: Int = 1200,
        val foodCalorieGoal: Int = 2000,
        val exerciseMinutes: Int = 35,
        val exerciseGoalMinutes: Int = 60,
        val standHours: Int = 7,
        val standGoal: Int = 12,
        // History for {shr_N} (N=1 previous … 9): newest reading LAST; when
        // empty, {shr_N} falls back to heartRate - 2*(N-1) like the old values.
        val heartRateHistory: List<Int> = emptyList(),
        val stressScore: Int = 35,

        // ── Sleep (CloX/BHT 2.0 extension, last night's session) ──
        val sleepHasData: Boolean = true,
        val sleepAsleepMin: Int = 444,
        val sleepBedtime: String = "23:41",
        val sleepWakeTime: String = "07:05",
        val sleepRemMin: Int = 92,
        val sleepLightMin: Int = 251,
        val sleepDeepMin: Int = 101,
        val sleepAwakeMin: Int = 18,
        // Total session length; {sslp} = asleep/session so the documented
        // example (444 asleep / 477 session → 93%) stays self-consistent.
        val sleepSessionMin: Int = 477,

        // ── Weather ──
        val temp: Int = 22,
        val tempHigh: Int = 25,
        val tempLow: Int = 15,
        val tempUnit: String = "C",
        val humidity: Int = 65,
        val windDir: Int = 225,
        val windSpeed: Int = 12,
        val cloudCover: Int = 40,
        val pressure: Int = 1013,
        val precipitation: Int = 2,
        val conditionText: String = "Partly Cloudy",
        val conditionIcon: String = "02d",
        val weatherLocation: String = "London",
        val sunrise: String = "06:30",
        val sunset: String = "18:45",
        val moonPhase: Int = 5,

        // ── Sensors ──
        val acceleration: Triple<Float, Float, Float> = Triple(0f, 9.8f, 0f),
        val gyro: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
        // ── Compass / GPS ──
        val compassHeading: Int = 45,
        val latitude: Double = 51.5074,
        val longitude: Double = -0.1278,
        val altitudeM: Int = 32,

        // ── Device / misc ──
        val notificationCount: Int = 3,
        val notificationAppName: String = "Messages",
        val notificationTitle: String = "Alice",
        val notificationContent: String = "Hey, are you free?",
        val volume: Int = 8,
        val accentColor1: String = "2196F3",
        val accentColor2: String = "FF5722",
        val accentColor3: String = "4CAF50",
        // Static device identity (editable for previews; live device data would
        // override these per-category).
        val watchName: String = "My Watch",
        val deviceName: String = "My Phone",
        val deviceModel: String = "Pixel 8",
        val deviceManufacturer: String = "Google",
        val isRound: Boolean = true,
        val hasCutout: Boolean = false,
        val isDimLoBit: Boolean = false,
        val langCode: String = "en",
        val langRegion: String = "US",
        val langFull: String = "English",
        val lastReboot: String = "2024-01-15 08:30",
        val osVersion: String = "15",
        val launcherVersion: String = "4.0",
        val screenBrightness: Int = 128,

        // ── Connectivity (source) ──
        val wifiConnected: Boolean = true,
        val wifiSignalBars: Int = 3,        // 0..4; {pws} = bars/4
        val wifiIp: String = "192.168.1.1",
        val cellularConnected: Boolean = false,
        val cellularSignalBars: Int = 2,    // 0..4; {ncs} = bars/4
        val bluetoothEnabled: Boolean = false,
        val gpsEnabled: Boolean = true,

        // ── Modes (source) ──
        val isBright: Boolean = true,       // bright/active vs dim/AOD → {abright}
        val darkMode: Boolean = false,
        val lowPower: Boolean = false,
        val is24hMode: Boolean = false,     // device 12/24h setting → {d24h}

        // ── Stopwatch (source; advanced by the preview while running) ──
        val stopwatchRunning: Boolean = false,
        val stopwatchMs: Long = 0,

        // ── Session clock (preview runtime, not user-editable) ──
        // Seconds since the preview session started; drives {c_elapsed}, {abss}
        // and {abssl}. Fed by WmPreviewController.sessionStartMs each tick.
        val elapsedSeconds: Double = 0.0,

        // ── Memory / storage in GB (source) ──
        val usedRamGb: Float = 3.2f,
        val totalRamGb: Float = 8f,
        val usedStorageGb: Float = 48f,
        val totalStorageGb: Float = 128f,

        // ── Alarm (source) — "HH:MM" or null for no alarm ──
        val alarmTime: String? = "07:30",

        // ── Global script variables ──
        // Top-level variables defined in the watchface's global script
        // (scripts/script.txt), keyed by name → raw Lua RHS expression. Referenced
        // from any attribute as a {name} tag; resolved live (the RHS may itself
        // contain tags / expressions). Populated via [parseGlobalVariables].
        val variables: Map<String, String> = emptyMap(),

        // ── Live global-script runtime ──
        // When the preview is running the face's global script as real Lua, this
        // points at that state so attribute expressions can call the script's own
        // functions (wf_tiletext(...)) and read live var_* globals. Null outside
        // the interactive preview (flatten/export/dialogs fall back to [variables]).
        val scriptRuntime: WmScriptRuntime? = null
    )

    // ── Derivation helpers (single place for the math, no inline magic) ──
    private fun pct(value: Int, goal: Int): Int =
        if (goal <= 0) 0 else (value * 100 / goal).coerceIn(0, 100)

    private fun pct(value: Float, goal: Float): Int =
        if (goal <= 0f) 0 else (value * 100f / goal).toInt().coerceIn(0, 100)

    private fun cToF(c: Int): Int = Math.round(c * 9f / 5f + 32f)

    private val bearings2 = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val bearings3 = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )
    private fun bearing2(deg: Int): String = bearings2[(Math.floorMod(Math.round(deg / 45f), 8))]
    private fun bearing3(deg: Int): String = bearings3[(Math.floorMod(Math.round(deg / 22.5f), 16))]

    /** Fraction of the day (0-1) for an "HH:MM" time string. */
    private fun fracOfDay(hhmm: String): String {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return String.format("%.2f", (h * 60 + m) / 1440.0)
    }

    /** CloX {ucolor_b}: brighten a RRGGBB hex by +64 per channel, clamped to 255. */
    private fun brightenColor(hex: String): String {
        val h = hex.trimStart('#').padStart(6, '0')
        return try {
            val r = (h.substring(0, 2).toInt(16) + 64).coerceAtMost(255)
            val g = (h.substring(2, 4).toInt(16) + 64).coerceAtMost(255)
            val b = (h.substring(4, 6).toInt(16) + 64).coerceAtMost(255)
            String.format("%02X%02X%02X", r, g, b)
        } catch (e: Exception) { h }
    }

    private fun gb1(v: Float) = String.format("%.1f", v)
    private fun pct1(part: Float, total: Float) = if (total <= 0f) "0" else String.format("%.1f", part / total * 100f)

    private fun minutesOfDay(hhmm: String): Int {
        val p = hhmm.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** Drop a trailing ".0" so whole numbers render as integers. */
    private fun fmtNum(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)

    // ── Spelled-out time helpers (CloX wf_tagdefs port) ──
    private val onesWords = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    private val tensWords = arrayOf("", "", "twenty", "thirty", "forty", "fifty")
    /** 0-54 → English words; compounds space-separated (46 → "forty six"). */
    private fun numWord(n: Int): String {
        val v = n.coerceAtLeast(0)
        if (v < 20) return onesWords[v]
        val t = v / 10
        val o = v % 10
        return if (o == 0) tensWords[t] else tensWords[t] + " " + onesWords[o]
    }
    // WM/UL word-clock split: 0 → "o'"/"clock"; 1-9 → "o'"/"three";
    // 10-19 → "eleven"/""; 20+ → "forty"/"six" ("" on exact tens).
    private fun timeWordParts(n: Int): Pair<String, String> {
        if (n == 0) return "o'" to "clock"
        if (n < 10) return "o'" to onesWords[n]
        if (n < 20) return onesWords[n] to ""
        val o = n % 10
        return (tensWords[n / 10] to if (o == 0) "" else onesWords[o])
    }
    // full form: "o'clock" / "o'three" (no space after o'), "eleven", "forty six"
    private fun timeWordFull(n: Int): String {
        val (a, b) = timeWordParts(n)
        return when {
            a == "o'" -> a + b
            b.isEmpty() -> a
            else -> "$a $b"
        }
    }

    private fun h12(hour: Int): Int = (hour % 12).let { if (it == 0) 12 else it }

    /** {wisday}: is [now] between sunrise and sunset (exclusive bounds). */
    private fun isDaytime(now: java.time.LocalDateTime, sunrise: String, sunset: String): Boolean {
        val min = now.hour * 60 + now.minute
        val r = minutesOfDay(sunrise)
        val s = minutesOfDay(sunset)
        if (r <= 0 && s <= 0) return now.hour >= 6 && now.hour < 18
        if (s <= r) return min >= r || min < s   // crosses midnight
        return min in r until s
    }

    /** {shr_N}: the N-th previous heart-rate reading (1 = previous). */
    private fun shrHistory(n: Int, state: WmPreviewState): String {
        state.heartRateHistory.getOrNull(state.heartRateHistory.size - n)?.let { return it.toString() }
        return (state.heartRate - 2 * n).coerceAtLeast(0).toString()
    }

    /** Zero-padded stopwatch split, matching CloX: h/m/s → "00", centis → "00", millis → "000". */
    private fun swZ(n: Long): String = "%02d".format(n)
    private fun swZ3(n: Long): String = "%03d".format(n)
    private fun swParts(ms: Long): LongArray =
        longArrayOf(ms / 3600000, ms % 3600000 / 60000, ms % 60000 / 1000, ms % 1000)

    // ── Reference data for pattern-resolved tags (preview only) ──
    private val wIcons = listOf("01d", "02d", "03d", "04d", "09d", "10d", "11d", "13d", "50d")
    private val wConds = listOf(
        "Clear", "Partly Cloudy", "Cloudy", "Overcast", "Showers",
        "Rain", "Thunderstorm", "Snow", "Fog"
    )
    // Preview world clocks: tagIndex -> (short label, long label, IANA zone).
    // Labels match WM_TAGS.md examples (tz1=Pacific, tz2=London, tz3=Tokyo).
    private val tzZones = mapOf(
        1 to Triple("Pacific", "US/Pacific", "America/Los_Angeles"),
        2 to Triple("London", "Europe/London", "Europe/London"),
        3 to Triple("Tokyo", "Asia/Tokyo", "Asia/Tokyo")
    )
    private data class CalEvt(
        val title: String, val begin: String, val end: String,
        val loc: String, val color: String, val cal: String
    )
    private val calEvents = mapOf(
        1 to CalEvt("Meeting", "14:00", "15:00", "Office", "FF5722", "Work"),
        2 to CalEvt("Gym", "18:00", "19:00", "Gym", "4CAF50", "Personal"),
        3 to CalEvt("Lunch", "12:00", "13:00", "Cafe", "2196F3", "Personal"),
        4 to CalEvt("Call", "16:00", "16:30", "", "FFC107", "Work")
    )

    private val reWfDay = Regex("""\{wf(\d+)d(th|tl|ci|ct|t)\}""")
    private val reWfHour = Regex("""\{wf(\d+)h(ci|ct|h|t)\}""")
    private val reDayN = Regex("""\{ddw([12])_(\d+)\}""")
    private val reDayFull = Regex("""\{ddww_(\d+)\}""")
    private val reTz = Regex("""\{tz(\d)(ll|l|t|om|o|dst|rh24|rh|rm)\}""")
    private val reCal = Regex("""\{c(\d+)(ex|title|cal|bd|ed|br|bp|ep|er|ad|t|b|e|l|c|i)\}""")
    private val reCounter =
        Regex("""\{c_(-?\d+(?:\.\d+)?)_(-?\d+(?:\.\d+)?)_(\d+(?:\.\d+)?)_(st|rp|rv)(?:_(\d+(?:\.\d+)?))?\}""")

    /**
     * Resolves parametric / indexed tag families that would be impractical to list
     * one-by-one: weather forecast (days 2-5 + hourly 1-12), future day names,
     * world clocks (tz1-3), media slots (m1-4), calendar events (c1-4) and the
     * counter/tween pattern {c_FROM_TO_DUR_MODE}. All values derive from
     * [WmPreviewState]/the clock so they animate and stay internally consistent.
     * Returns null when [tag] is not one of these families.
     */
    private fun resolveDynamic(tag: String, state: WmPreviewState): String? {
        val now = state.now
        reWfDay.matchEntire(tag)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (m.groupValues[2]) {
                "t" -> (state.temp + ((n % 3) - 1)).toString()
                "th" -> (state.tempHigh + n).toString()
                "tl" -> (state.tempLow - n / 2).toString()
                "ci" -> wIcons[n % wIcons.size]
                "ct" -> wConds[n % wConds.size]
                else -> null
            }
        }
        reWfHour.matchEntire(tag)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (m.groupValues[2]) {
                "h" -> ((now.hour + n) % 24).toString().padStart(2, '0')
                "t" -> (state.temp + ((n % 5) - 2)).toString()
                "ci" -> wIcons[n % wIcons.size]
                "ct" -> wConds[n % wConds.size]
                else -> null
            }
        }
        reDayN.matchEntire(tag)?.let { m ->
            val d = now.dayOfWeek.plus(m.groupValues[2].toLong())
            return if (m.groupValues[1] == "1")
                d.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.US)
            else d.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).take(2)
        }
        reDayFull.matchEntire(tag)?.let { m ->
            return now.dayOfWeek.plus(m.groupValues[1].toLong())
                .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
        }
        reTz.matchEntire(tag)?.let { m ->
            val z = tzZones[m.groupValues[1].toInt()] ?: return null
            val zone = java.time.ZoneId.of(z.third)
            val zdt = java.time.ZonedDateTime.now(zone)
            val offSec = zdt.offset.totalSeconds
            return when (m.groupValues[2]) {
                "l" -> z.first
                "ll" -> z.second
                "t" -> "%02d:%02d".format(zdt.hour, zdt.minute)
                // Documented as a string offset, e.g. "-7:00" / "+1:00".
                "o" -> "%s%d:%02d".format(if (offSec < 0) "-" else "+",
                    Math.abs(offSec) / 3600, (Math.abs(offSec) % 3600) / 60)
                "om" -> (offSec / 60).toString()
                "dst" -> zone.rules.isDaylightSavings(zdt.toInstant()).toString()
                "rh" -> ((zdt.hour % 12) * 30 + zdt.minute * 0.5f).toString()
                "rh24" -> (zdt.hour * 15 + zdt.minute * 0.25f).toString()
                "rm" -> (zdt.minute * 6).toString()
                else -> null
            }
        }
        reCal.matchEntire(tag)?.let { m ->
            val n = m.groupValues[1].toInt()
            val field = m.groupValues[2]
            val e = calEvents[n]
            if (field == "ex") return (e != null).toString()
            if (e == null) return ""
            val date = now.toLocalDate().toString()
            return when (field) {
                "t", "title" -> e.title
                "b" -> e.begin
                "e" -> e.end
                "bd" -> date
                "ed" -> date
                "ad" -> "false"
                "l" -> e.loc
                "c" -> e.color
                "cal" -> e.cal
                "i" -> n.toString()
                // br/er: event begin/end as a 12h-dial rotation (0-360) —
                // CloX port ((h%12)+m/60)/12*360, NOT the 24h fraction.
                "br" -> fmtNum((((minutesOfDay(e.begin) / 60) % 12) + (minutesOfDay(e.begin) % 60) / 60.0) / 12.0 * 360.0)
                "bp" -> ((minutesOfDay(e.begin) * 100) / 1440).toString()
                "er" -> fmtNum((((minutesOfDay(e.end) / 60) % 12) + (minutesOfDay(e.end) % 60) / 60.0) / 12.0 * 360.0)
                "ep" -> ((minutesOfDay(e.end) * 100) / 1440).toString()
                else -> null
            }
        }
        reCounter.matchEntire(tag)?.let { m ->
            val from = m.groupValues[1].toDouble()
            val to = m.groupValues[2].toDouble()
            val dur = m.groupValues[3].toDouble().coerceAtLeast(0.001)
            val delay = m.groupValues[5].toDoubleOrNull() ?: 0.0
            val elapsed = now.toLocalTime().toNanoOfDay() / 1_000_000_000.0
            val t = when (m.groupValues[4]) {
                "st" -> (elapsed / dur).coerceIn(0.0, 1.0)
                "rp" -> ((elapsed % (dur + delay)) / dur).coerceIn(0.0, 1.0)
                "rv" -> {
                    val ph = elapsed % (dur * 2)
                    (if (ph <= dur) ph / dur else 2 - ph / dur).coerceIn(0.0, 1.0)
                }
                else -> 0.0
            }
            return fmtNum(from + (to - from) * t)
        }
        return null
    }

    val categories: LinkedHashMap<String, List<TagEntry>> = linkedMapOf(
        // ... (rest of categories remain the same)
        "app_time" to listOf(
            TagEntry("{dh}", "app_hour_12_hr_no_zero", "11"),
            TagEntry("{dhz}", "app_hour_12_hr_with_zero", "11"),
            TagEntry("{dh11}", "app_hour_12_hr_format", "11"),
            TagEntry("{dh11z}", "app_hour_12_hr_with_zero", "11"),
            TagEntry("{dh24}", "app_hour_24_hr", "23"),
            TagEntry("{dh24z}", "app_hour_24_hr_with_zero", "23"),
            TagEntry("{dh23}", "app_hour_23_hr_midnight_0", "22"),
            TagEntry("{dh23z}", "app_hour_23_hr_with_zero", "22"),
            TagEntry("{dm}", "app_minute", "45"),
            TagEntry("{dmz}", "app_minute_with_zero", "45"),
            TagEntry("{ds}", "app_second", "30"),
            TagEntry("{dsz}", "app_second_with_zero", "30"),
            TagEntry("{dss}", "app_milliseconds", "500"),
            TagEntry("{dssz}", "app_milliseconds_3_digits", "500"),
            TagEntry("{dsps}", "app_ms_within_second_smooth", "500"),
            TagEntry("{da}", "app_am_pm_indicator", "PM"),
            TagEntry("{dtp}", "app_time_as_fraction_of_24_hours_0_1", "0.763"),
            TagEntry("{dutcoff}", "app_utc_offset_e_g_0100", "+0100"),
            TagEntry("{depoch}", "app_unix_timestamp_seconds", "1700000000"),
            TagEntry("{dz}", "app_timezone_abbreviation_e_g_cet", "CET"),
            TagEntry("{drs}", "app_second_hand_rotation_0_360", "180"),
            TagEntry("{drss}", "app_second_hand_rotation_smooth_0_360", "180"),
            TagEntry("{drm}", "app_minute_hand_rotation_smooth_0_360", "270"),
            TagEntry("{drms}", "app_millisecond_rotation_0_360_per_second", "216.0"),
            TagEntry("{drh}", "app_hour_hand_rotation_12_hr_0_360", "330"),
            TagEntry("{drh0}", "app_hour_hand_12_hr_no_minute_offset", "330"),
            TagEntry("{drh24}", "app_hour_hand_rotation_24_hr_0_360", "165"),
            TagEntry("{dhutc12}", "app_utc_hour_12_hr_no_zero", "11"),
            TagEntry("{dhutc12z}", "app_utc_hour_12_hr_zero_padded", "11"),
            TagEntry("{dhutc24}", "app_utc_hour_24_hr_no_zero", "23"),
            TagEntry("{dhutc24z}", "app_utc_hour_24_hr_zero_padded", "23"),
            TagEntry("{d24h}", "app_is_24h_mode_enabled", "true"),
            TagEntry("{dh23i}", "app_hour_always_24h_ignores_device_setting", "18"),
            TagEntry("{dh23tt}", "app_hour_0_23_tens_digit", "1"),
            TagEntry("{dh23to}", "app_hour_0_23_ones_digit", "8"),
            TagEntry("{dhtt}", "app_hour_1_12_tens_digit", "0"),
            TagEntry("{dhto}", "app_hour_1_12_ones_digit", "6"),
            TagEntry("{dh11tt}", "app_hour_0_11_tens_digit", "0"),
            TagEntry("{dh11to}", "app_hour_0_11_ones_digit", "6"),
            TagEntry("{dh24tt}", "app_hour_1_24_tens_digit", "1"),
            TagEntry("{dh24to}", "app_hour_1_24_ones_digit", "8"),
            TagEntry("{dmt}", "app_minute_tens_digit", "4"),
            TagEntry("{dmo}", "app_minute_ones_digit", "5"),
            TagEntry("{dst}", "app_second_tens_digit", "3"),
            TagEntry("{dso}", "app_second_ones_digit", "0"),
            TagEntry("{dht}", "app_hour_text_1_12", "six"),
            TagEntry("{dh24t}", "app_hour_text_1_24", "eighteen"),
            TagEntry("{dmat}", "app_minute_text_full", "twenty two"),
            TagEntry("{dmtt}", "app_minute_text_tens", "twenty"),
            TagEntry("{dmot}", "app_minute_text_ones", "two"),
            TagEntry("{dsat}", "app_second_text_full", "thirty one"),
            TagEntry("{dstt}", "app_second_text_tens", "thirty"),
            TagEntry("{dsot}", "app_second_text_ones", "one"),
            TagEntry("{c_elapsed}", "app_seconds_elapsed_since_face_loaded", "5.2"),
        ),
        "app_date" to listOf(
            TagEntry("{dd}", "app_day_of_month_1_31", "25"),
            TagEntry("{ddz}", "app_day_of_month_with_zero", "25"),
            TagEntry("{ddy}", "app_day_of_year_1_366", "172"),
            TagEntry("{ddim}", "app_days_in_current_month", "28"),
            TagEntry("{ddw}", "app_day_name_3_chars", "Mon"),
            TagEntry("{ddw1}", "app_day_name_1_char", "M"),
            TagEntry("{ddw2}", "app_day_name_2_chars", "Mo"),
            TagEntry("{ddww}", "app_day_name_full", "Monday"),
            TagEntry("{ddw0}", "app_day_of_week_0_sunday", "1"),
            TagEntry("{dn}", "app_month_number_1_12", "2"),
            TagEntry("{dnn}", "app_month_number_with_zero", "02"),
            TagEntry("{dnnn}", "app_month_name_3_chars", "Feb"),
            TagEntry("{dnnnn}", "app_month_name_full", "February"),
            TagEntry("{dy}", "app_year_2_digits", "26"),
            TagEntry("{dyy}", "app_year_4_digits", "2026"),
            TagEntry("{dw}", "app_week_of_year_1_53", "8"),
            TagEntry("{dwm}", "app_week_of_month_1_5", "3"),
            TagEntry("{ddiy}", "app_days_in_current_year_365_366", "366"),
            TagEntry("{ddw_1}", "app_day_name_1_day_3_chars", "Tue"),
            TagEntry("{ddw_2}", "app_day_name_2_days_3_chars", "Wed"),
            TagEntry("{ddw_3}", "app_day_name_3_days_3_chars", "Wed"),
            TagEntry("{ddw_4}", "app_day_name_4_days_3_chars", "Thu"),
            TagEntry("{ddw_5}", "app_day_name_5_days_3_chars", "Fri"),
            TagEntry("{ddw_6}", "app_day_name_6_days_3_chars", "Sat"),
            TagEntry("{ddww_1}", "app_full_day_name_1_day", "Tuesday"),
            TagEntry("{ddww_2}", "app_full_day_name_2_days", "Wednesday"),
            TagEntry("{ddww_3}", "app_full_day_name_3_days", "Thursday"),
            TagEntry("{ddww_4}", "app_full_day_name_4_days", "Friday"),
            TagEntry("{ddww_5}", "app_full_day_name_5_days", "Saturday"),
            TagEntry("{ddww_6}", "app_full_day_name_6_days", "Sunday"),
            TagEntry("{ddw1_1}", "app_day_name_1_day_1_char", "T"),
            TagEntry("{ddw1_2}", "app_day_name_2_days_1_char", "W"),
            TagEntry("{ddw1_3}", "app_day_name_3_days_1_char", "T"),
            TagEntry("{ddw1_4}", "app_day_name_4_days_1_char", "F"),
            TagEntry("{ddw1_5}", "app_day_name_5_days_1_char", "S"),
            TagEntry("{ddw1_6}", "app_day_name_6_days_1_char", "S"),
            TagEntry("{ddw2_1}", "app_day_name_1_day_2_chars", "Tu"),
            TagEntry("{ddw2_2}", "app_day_name_2_days_2_chars", "We"),
            TagEntry("{ddw2_3}", "app_day_name_3_days_2_chars", "Th"),
            TagEntry("{ddw2_4}", "app_day_name_4_days_2_chars", "Fr"),
            TagEntry("{ddw2_5}", "app_day_name_5_days_2_chars", "Sa"),
            TagEntry("{ddw2_6}", "app_day_name_6_days_2_chars", "Su"),
        ),
        "app_battery" to listOf(
            TagEntry("{bl}", "app_watch_battery_level_0_100", "75"),
            TagEntry("{blp}", "app_watch_battery_level_with_sign", "75%"),
            TagEntry("{br}", "app_watch_battery_rotation_3_6_per", "270"),
            TagEntry("{bc}", "app_watch_charging_state_charging_discharging", "Discharging"),
            TagEntry("{bv}", "app_watch_battery_voltage_v", "3.805"),
            TagEntry("{bi}", "app_watch_battery_current_ma", "350"),
            TagEntry("{btc}", "app_watch_battery_temperature_c", "36"),
            TagEntry("{btf}", "app_watch_battery_temperature_f", "97"),
            TagEntry("{btcd}", "app_watch_battery_temperature_with_c_unit", "36°C"),
            TagEntry("{btfd}", "app_watch_battery_temperature_with_f_unit", "97°F"),
            TagEntry("{pbl}", "app_phone_battery_level_0_100", "82"),
            TagEntry("{pblp}", "app_phone_battery_level_with_sign", "82%"),
            TagEntry("{pbr}", "app_phone_battery_rotation_3_6_per", "295"),
            TagEntry("{pbc}", "app_phone_charging_state_charging_discharging", "Charging"),
            TagEntry("{pbtc}", "app_phone_battery_temperature_c", "34"),
            TagEntry("{pbtf}", "app_phone_battery_temperature_f", "93"),
            TagEntry("{pbtcd}", "app_phone_battery_temperature_with_c_unit", "34°C"),
            TagEntry("{pbtfd}", "app_phone_battery_temperature_with_f_unit", "93°F"),
        ),
        "app_health" to listOf(
            TagEntry("{ssc}", "app_step_count", "8500"),
            TagEntry("{sscp}", "app_steps_as_of_daily_goal", "85"),
            TagEntry("{stsc}", "app_step_count_daily_goal", "10000"),
            TagEntry("{shr}", "app_heart_rate_bpm", "72"),
            TagEntry("{sthr}", "app_max_recorded_heart_rate", "180"),
            TagEntry("{shrp}", "app_heart_rate_as_of_max", "40"),
            TagEntry("{shr_1}", "app_heart_rate_previous", "70"),
            TagEntry("{shr_2}", "app_heart_rate_two_readings_ago", "68"),
            TagEntry("{shr_3}", "app_heart_rate_three_readings_ago", "66"),
            TagEntry("{shr_4}", "app_heart_rate_four_readings_ago", "64"),
            TagEntry("{shr_5}", "app_heart_rate_five_readings_ago", "62"),
            TagEntry("{shr_6}", "app_heart_rate_six_readings_ago", "60"),
            TagEntry("{shr_7}", "app_heart_rate_seven_readings_ago", "58"),
            TagEntry("{shr_8}", "app_heart_rate_eight_readings_ago", "56"),
            TagEntry("{shr_9}", "app_heart_rate_nine_readings_ago", "54"),
            TagEntry("{sstr}", "app_stress_score_0_100", "35"),
            TagEntry("{scal}", "app_calories_burned_active", "420"),
            TagEntry("{scalp}", "app_calories_as_of_goal", "60"),
            TagEntry("{stcal}", "app_calorie_goal", "700"),
            TagEntry("{sdst}", "app_distance_traveled", "6.2"),
            TagEntry("{sdstu}", "app_distance_unit_km_or_mi", "KM"),
            TagEntry("{sdstp}", "app_distance_as_of_goal", "62"),
            TagEntry("{stdst}", "app_distance_goal", "10"),
            TagEntry("{sbo}", "app_blood_oxygen_spo2", "98"),
            TagEntry("{hfc}", "app_flights_climbed_floors", "8"),
            TagEntry("{ham}", "app_food_calories_logged_kcal", "1200"),
            TagEntry("{htam}", "app_food_calorie_goal_kcal", "2000"),
            TagEntry("{hae}", "app_exercise_time_minutes", "35"),
            TagEntry("{htae}", "app_exercise_time_goal_minutes", "60"),
            TagEntry("{has}", "app_stand_hours_completed", "7"),
            TagEntry("{htas}", "app_stand_hours_goal", "12"),
        ),
        "app_sleep" to listOf(
            TagEntry("{sslm}", "app_minutes_asleep_last_night", "444"),
            TagEntry("{sslh}", "app_hours_asleep_one_decimal", "7.4"),
            TagEntry("{sslt}", "app_time_asleep_h_mm", "7:24"),
            TagEntry("{sslbt}", "app_bedtime_hh_mm", "23:41"),
            TagEntry("{sslwt}", "app_wake_time_hh_mm", "07:05"),
            TagEntry("{sslrm}", "app_rem_minutes_2", "92"),
            TagEntry("{ssllm}", "app_light_sleep_minutes", "251"),
            TagEntry("{ssldm}", "app_deep_sleep_minutes", "101"),
            TagEntry("{sslam}", "app_awake_minutes_during_session", "18"),
            TagEntry("{sslp}", "app_sleep_efficiency_asleep_session", "93"),
        ),
        "app_weather" to listOf(
            TagEntry("{wt}", "app_current_temperature", "22"),
            TagEntry("{wtd}", "app_temp_with_unit_e_g_22_c", "22°C"),
            TagEntry("{wth}", "app_forecast_high_temperature", "25"),
            TagEntry("{wthd}", "app_forecast_high_with_unit", "25°C"),
            TagEntry("{wtl}", "app_forecast_low_temperature", "15"),
            TagEntry("{wtld}", "app_forecast_low_with_unit", "15°C"),
            TagEntry("{wm}", "app_temperature_unit_c_f", "C"),
            TagEntry("{wci}", "app_weather_icon_code_e_g_01d", "02d"),
            TagEntry("{wct}", "app_weather_condition_text", "Partly Cloudy"),
            TagEntry("{wwd}", "app_wind_direction_0_360", "180"),
            TagEntry("{wwdb}", "app_wind_direction_2_chars", "SW"),
            TagEntry("{wwdbb}", "app_wind_direction_3_chars", "SSW"),
            TagEntry("{wws}", "app_wind_speed", "12"),
            TagEntry("{wh}", "app_humidity_0_100", "65"),
            TagEntry("{whp}", "app_humidity_formatted", "65%"),
            TagEntry("{wcl}", "app_cloud_cover_0_100", "40"),
            TagEntry("{wp}", "app_atmospheric_pressure", "1013"),
            TagEntry("{wr}", "app_precipitation_mm", "2"),
            TagEntry("{wsr}", "app_sunrise_time_hh_mm", "06:30"),
            TagEntry("{wss}", "app_sunset_time_hh_mm", "18:45"),
            TagEntry("{wsrp}", "app_sunrise_as_fraction_of_day", "0.27"),
            TagEntry("{wssp}", "app_sunset_as_fraction_of_day", "0.78"),
            TagEntry("{wmp}", "app_moon_phase_1_young_5_full_9_old", "5"),
            TagEntry("{wisday}", "app_is_daytime", "true"),
            TagEntry("{wlu}", "app_weather_last_update_timestamp", "1700000000"),
            TagEntry("{wl}", "app_weather_location", "London"),
            TagEntry("{wml}", "app_manual_weather_location", "London"),
        ),
        "wm_tag_group_weather_forecast" to buildList {
            // Daily forecast: day 0 = today … day 5
            for (n in 0..5) {
                val day = if (n == 0) "Today" else "Day +$n"
                add(TagEntry("{wf${n}dt}", "app_x_forecast_temp", "22"))
                add(TagEntry("{wf${n}dth}", "app_x_forecast_high", "25"))
                add(TagEntry("{wf${n}dtl}", "app_x_forecast_low", "15"))
                add(TagEntry("{wf${n}dci}", "app_x_forecast_icon_code", "02d"))
                add(TagEntry("{wf${n}dct}", "app_x_forecast_condition", "Partly Cloudy"))
            }
            // Hourly forecast: hour +1 … +12
            for (n in 1..12) {
                add(TagEntry("{wf${n}hh}", "app_forecast_xh_hour_label", "14"))
                add(TagEntry("{wf${n}ht}", "app_forecast_xh_temp", "22"))
                add(TagEntry("{wf${n}hci}", "app_forecast_xh_icon_code", "02d"))
                add(TagEntry("{wf${n}hct}", "app_forecast_xh_condition", "Partly Cloudy"))
            }
        },
        "app_sensors" to listOf(
            TagEntry("{sax}", "app_accelerometer_x_m_s", "0.1"),
            TagEntry("{say}", "app_accelerometer_y_m_s", "9.7"),
            TagEntry("{saz}", "app_accelerometer_z_m_s", "0.3"),
            TagEntry("{sgx}", "app_gyroscope_x_frame_parallax", "0.02"),
            TagEntry("{sgy}", "app_gyroscope_y_frame_parallax", "0.01"),
            TagEntry("{sgz}", "app_gyroscope_z_frame", "0.0"),
            TagEntry("{sprs}", "app_barometric_pressure_hpa", "1013"),
        ),
        "app_compass_gps" to listOf(
            TagEntry("{scr}", "app_compass_rotation_0_360", "45"),
            TagEntry("{sct}", "app_compass_heading_0_360", "45"),
            TagEntry("{sctd}", "app_compass_heading_in_degrees", "45°"),
            TagEntry("{sctdb}", "app_degrees_bearing_2_letter_e_g_45_ne", "45°NE"),
            TagEntry("{sctdbb}", "app_degrees_bearing_3_letter_e_g_45_nne", "45°NNE"),
            TagEntry("{scb}", "app_compass_bearing_2_letters_e_g_ne", "NE"),
            TagEntry("{scbb}", "app_compass_bearing_3_letters_e_g_nne", "NNE"),
            TagEntry("{alat}", "app_current_latitude_decimal", "51.5"),
            TagEntry("{alon}", "app_current_longitude_decimal", "-0.1"),
            TagEntry("{alatd}", "app_latitude_degrees_only", "51.5"),
            TagEntry("{alond}", "app_longitude_degrees_only", "0.1"),
            TagEntry("{alatdd}", "app_latitude_with_direction_e_g_51_5_n", "51.5°N"),
            TagEntry("{alondd}", "app_longitude_with_direction_e_g_0_1_e", "0.1°E"),
            TagEntry("{aalt}", "app_altitude_meters", "32"),
            TagEntry("{aloc}", "app_location_name_city", "London"),
            TagEntry("{aw3w}", "app_what3words_address_full", "filed.shot.spar"),
            TagEntry("{aw3w1}", "app_what3words_word_1", "filed"),
            TagEntry("{aw3w2}", "app_what3words_word_2", "shot"),
            TagEntry("{aw3w3}", "app_what3words_word_3", "spar"),
        ),
        "app_stopwatch" to listOf(
            TagEntry("{swr}", "app_stopwatch_running_true_false", "false"),
            TagEntry("{swh}", "app_stopwatch_hours_zero_padded", "00"),
            TagEntry("{swm}", "app_stopwatch_minutes_zero_padded", "00"),
            TagEntry("{sws}", "app_stopwatch_seconds_zero_padded", "00"),
            TagEntry("{swss}", "app_centiseconds_2_digits", "00"),
            TagEntry("{swsss}", "app_milliseconds_3_digits", "000"),
            TagEntry("{swsst}", "app_total_milliseconds", "0"),
            TagEntry("{swrm}", "app_minute_hand_rotation_0_360", "0"),
            TagEntry("{swrs}", "app_second_hand_rotation_0_360", "0"),
            TagEntry("{swrss}", "app_second_hand_smooth_0_360", "0"),
            TagEntry("{tswh}", "app_hours_ul_only_alias", "00"),
            TagEntry("{tswm}", "app_minutes_ul_only_alias", "00"),
            TagEntry("{tsws}", "app_seconds_ul_only_alias", "00"),
            TagEntry("{tswss}", "app_centiseconds_ul_only_alias", "00"),
            TagEntry("{tswsss}", "app_milliseconds_ul_only_alias", "000"),
            TagEntry("{tswsst}", "app_total_milliseconds_ul_only_alias", "0"),
        ),
        "app_network_device" to listOf(
            TagEntry("{pwc}", "app_wi_fi_connected_true_false", "true"),
            TagEntry("{pws}", "app_wi_fi_signal_strength_0_1", "0.8"),
            TagEntry("{nwip}", "app_wi_fi_ip_address", "192.168.1.1"),
            TagEntry("{ncc}", "app_cellular_connected_true_false", "false"),
            TagEntry("{ncs}", "app_cellular_signal_strength_0_1", "0.6"),
            TagEntry("{nc}", "app_any_network_connected_true_false", "true"),
            TagEntry("{nbc}", "app_bluetooth_connected_true_false", "false"),
            TagEntry("{ngc}", "app_gps_enabled_true_false", "true"),
            TagEntry("{cnc}", "app_unread_notification_count_0_99", "3"),
            TagEntry("{cn1a}", "app_notification_1_app_name", "Messages"),
            TagEntry("{cn1t}", "app_notification_1_title", "Alice"),
            TagEntry("{cn1c}", "app_notification_1_content_text", "Hey, are you free?"),
            TagEntry("{aname}", "app_phone_device_name", "My Phone"),
            TagEntry("{amodel}", "app_device_model_name", "Pixel 8"),
            TagEntry("{aman}", "app_device_manufacturer", "Google"),
            TagEntry("{awname}", "app_watch_name", "My Watch"),
            TagEntry("{around}", "app_screen_is_round", "true"),
            TagEntry("{atyre}", "app_has_flat_tyre_cutout", "false"),
            TagEntry("{aos}", "app_os_type", "Android"),
            TagEntry("{aosv}", "app_android_api_level", "34"),
            TagEntry("{alangcode}", "app_system_language_code_e_g_en", "en"),
            TagEntry("{alangreg}", "app_system_language_region_e_g_us", "US"),
            TagEntry("{alangfull}", "app_full_language_e_g_english", "English"),
            TagEntry("{ulver}", "app_universal_launcher_version", "4.0"),
            TagEntry("{abright}", "app_screen_in_bright_mode_true_false", "true"),
            TagEntry("{abss}", "app_milliseconds_since_bright_mode_1_if_dim", "283608"),
            TagEntry("{abssl}", "app_seconds_since_bright_capped_at_30_1_if_dim", "13.6"),
            TagEntry("{abrt}", "app_screen_brightness_level_0_255", "128"),
            TagEntry("{abtc}", "app_bluetooth_enabled", "true"),
            TagEntry("{adimlo}", "app_dim_mode_supports_lo_bit_only", "false"),
            TagEntry("{areboot}", "app_last_reboot_time", "2024-01-15 08:30"),
            TagEntry("{adark}", "app_dark_mode_enabled_true_false", "false"),
            TagEntry("{avol}", "app_current_system_volume_0_15", "8"),
            TagEntry("{alowpw}", "app_low_power_mode_active_true_false", "false"),
            TagEntry("{ucolor}", "app_ui_accent_color_1_rrggbb", "2196F3"),
            TagEntry("{ucolor2}", "app_ui_accent_color_2_rrggbb", "FF5722"),
            TagEntry("{ucolor3}", "app_ui_accent_color_3_rrggbb", "4CAF50"),
            TagEntry("{ucolor_b}", "app_ui_accent_color_1_brighter_rrggbb", "42A5F5"),
        ),
        "app_memory_storage" to listOf(
            TagEntry("{amu}", "app_used_ram_gb", "3.2"),
            TagEntry("{amuf}", "app_used_ram_formatted", "3.2GB"),
            TagEntry("{amup}", "app_used_ram", "60"),
            TagEntry("{amf}", "app_free_ram_gb", "2.1"),
            TagEntry("{amff}", "app_free_ram_formatted", "2.1GB"),
            TagEntry("{amfp}", "app_free_ram", "40"),
            TagEntry("{amt}", "app_total_ram_gb", "8"),
            TagEntry("{amtf}", "app_total_ram_formatted", "8GB"),
            TagEntry("{adsu}", "app_used_storage_gb", "15.5"),
            TagEntry("{adsuf}", "app_used_storage_formatted", "15.5 GB"),
            TagEntry("{adsup}", "app_used_storage", "14.6"),
            TagEntry("{adsf}", "app_free_storage_gb", "90.9"),
            TagEntry("{adsff}", "app_free_storage_formatted", "90.9 GB"),
            TagEntry("{adsfp}", "app_free_storage", "85.4"),
            TagEntry("{adst}", "app_total_storage_gb", "106.4"),
            TagEntry("{adstf}", "app_total_storage_formatted", "106.4 GB"),
        ),
        "app_alarm" to listOf(
            TagEntry("{aalo}", "app_alarm_set_within_24h_0_1", "1"),
            TagEntry("{aalh23z}", "app_alarm_hour_00_23", "07"),
            TagEntry("{aalmz}", "app_alarm_minute_00_59", "30"),
        ),
        "app_calendar" to buildList {
            add(TagEntry("{cex}", "app_any_events_exist_true_false", "true"))
            for (n in 1..4) {
                add(TagEntry("{c${n}ex}", "app_event_x_exists", "true"))
                add(TagEntry("{c${n}t}", "app_event_x_title", "Meeting"))
                add(TagEntry("{c${n}b}", "app_event_x_start_time_hh_mm", "14:00"))
                add(TagEntry("{c${n}e}", "app_event_x_end_time_hh_mm", "15:00"))
                add(TagEntry("{c${n}bd}", "app_event_x_begin_date", "2026-02-25"))
                add(TagEntry("{c${n}ed}", "app_event_x_end_date", "2026-02-25"))
                add(TagEntry("{c${n}ad}", "app_event_x_all_day_true_false", "false"))
                add(TagEntry("{c${n}l}", "app_event_x_location", "Office"))
                add(TagEntry("{c${n}c}", "app_event_x_color_rrggbb", "FF5722"))
                add(TagEntry("{c${n}cal}", "app_event_x_calendar_name", "Work"))
                add(TagEntry("{c${n}i}", "app_event_x_index", n.toString()))
                add(TagEntry("{c${n}br}", "app_event_x_start_as_rotation", if (n == 1) "60" else "210"))
                add(TagEntry("{c${n}bp}", "app_event_x_start_as_of_day", "58"))
                add(TagEntry("{c${n}er}", "app_event_x_end_as_rotation", if (n == 1) "90" else "225"))
                add(TagEntry("{c${n}ep}", "app_event_x_end_as_of_day", "62"))
            }
        },
        // Pattern: {c_FROM_TO_DURATION_MODE[_DELAY]} (MODE = st/rp/rv). Any valid
        // instance resolves at render time; these are the documented examples.
        "app_counters_tweens" to listOf(
            TagEntry("{c_elapsed}", "app_seconds_elapsed_since_face_loaded", "173.604"),
            TagEntry("{c_0_100_2_st}", "app_n0_100_in_2s_then_stop", "100"),
            TagEntry("{c_0_100_2_rp}", "app_n0_100_in_2s_then_repeat", "80.2101"),
            TagEntry("{c_0_100_2_rv}", "app_n0_100_in_2s_then_reverse", "80.2101"),
            TagEntry("{c_0_100_2_rv_2}", "app_same_with_2s_start_delay", "19.7899"),
        ),
        "app_time_zones" to listOf(
            TagEntry("{tz1l}", "app_time_zone_1_short_label", "Pacific"),
            TagEntry("{tz1ll}", "app_time_zone_1_long_label", "US/Pacific"),
            TagEntry("{tz1o}", "app_time_zone_1_utc_offset_string", "-7:00"),
            TagEntry("{tz1om}", "app_time_zone_1_utc_offset_minutes", "-420"),
            TagEntry("{tz1dst}", "app_time_zone_1_dst_active_true_false", "true"),
            TagEntry("{tz1t}", "app_time_zone_1_current_time_hh_mm", "14:30"),
            TagEntry("{tz1rh}", "app_time_zone_1_hour_hand_rotation_12_hr", "285"),
            TagEntry("{tz1rh24}", "app_time_zone_1_hour_hand_rotation_24_hr", "217"),
            TagEntry("{tz1rm}", "app_time_zone_1_minute_hand_rotation", "180"),
            TagEntry("{tz2l}", "app_time_zone_2_short_label", "London"),
            TagEntry("{tz2ll}", "app_time_zone_2_long_label", "Europe/London"),
            TagEntry("{tz2o}", "app_time_zone_2_utc_offset_string", "+1:00"),
            TagEntry("{tz2om}", "app_time_zone_2_utc_offset_minutes", "60"),
            TagEntry("{tz2dst}", "app_time_zone_2_dst_active_true_false", "true"),
            TagEntry("{tz2t}", "app_time_zone_2_current_time_hh_mm", "19:30"),
            TagEntry("{tz2rh}", "app_time_zone_2_hour_hand_rotation_12_hr", "165"),
            TagEntry("{tz2rh24}", "app_time_zone_2_hour_hand_rotation_24_hr", "170.25"),
            TagEntry("{tz2rm}", "app_time_zone_2_minute_hand_rotation", "126.6"),
            TagEntry("{tz3l}", "app_time_zone_3_short_label", "Tokyo"),
            TagEntry("{tz3ll}", "app_time_zone_3_long_label", "Asia/Tokyo"),
            TagEntry("{tz3o}", "app_time_zone_3_utc_offset_string", "+9:00"),
            TagEntry("{tz3om}", "app_time_zone_3_utc_offset_minutes", "540"),
            TagEntry("{tz3dst}", "app_time_zone_3_dst_active_true_false", "false"),
            TagEntry("{tz3t}", "app_time_zone_3_current_time_hh_mm", "04:30"),
            TagEntry("{tz3rh}", "app_time_zone_3_hour_hand_rotation_12_hr", "135"),
            TagEntry("{tz3rh24}", "app_time_zone_3_hour_hand_rotation_24_hr", "290.25"),
            TagEntry("{tz3rm}", "app_time_zone_3_minute_hand_rotation", "127.4"),
        ),
    )

    fun resolveTag(tag: String, state: WmPreviewState): String {
        // Preview propagation path: return the centrally-maintained value for this tag.
        // The read is snapshot-tracked, so the calling layer recomposes only when THIS
        // tag's value changes. (Only present for tags actually used on the face.)
        state.tagOverride?.let { ov -> ov[tag]?.let { return it } }
        val now = state.now
        return when (tag) {
            "{dh}" -> (now.hour % 12).let { if (it == 0) 12 else it }.toString()
            "{dhz}" -> (now.hour % 12).let { if (it == 0) 12 else it }.toString().padStart(2, '0')
            "{dh11}" -> (now.hour % 12).toString()
            "{dh11z}" -> (now.hour % 12).toString().padStart(2, '0')
            "{dh24}" -> now.hour.let { if (it == 0) 24 else it }.toString()
            "{dh24z}" -> now.hour.let { if (it == 0) 24 else it }.toString().padStart(2, '0')
            "{dh23}" -> now.hour.toString()
            "{dh23z}" -> now.hour.toString().padStart(2, '0')
            "{dm}" -> now.minute.toString()
            "{dmz}" -> now.minute.toString().padStart(2, '0')
            "{ds}" -> now.second.toString()
            "{dsz}" -> now.second.toString().padStart(2, '0')
            "{dss}" -> (now.nano / 1000000).toString()
            "{dssz}" -> (now.nano / 1000000).toString().padStart(3, '0')
            // {dsps} = milliseconds within the MINUTE (0–59999), per CloX — not just within the second.
            "{dsps}" -> (now.second * 1000 + now.nano / 1000000).toString()
            "{dh23i}" -> now.hour.toString()
            "{da}" -> if (now.hour < 12) "AM" else "PM"
            "{dtp}" -> String.format("%.6f", (now.hour * 3600 + now.minute * 60 + now.second) / 86400.0)
            "{depoch}" -> (now.toEpochSecond(java.time.ZoneOffset.UTC)).toString()
            // CloX {dz}: timezone ABBREVIATION ("CET"), not the full name.
            "{dz}" -> java.util.TimeZone.getDefault().getDisplayName(
                false, java.util.TimeZone.SHORT, java.util.Locale.US)
            // CloX {dutcoff}: ±HHMM with NO colon ("+0800"), unlike ISO +08:00.
            "{dutcoff}" -> java.time.OffsetDateTime.now().offset.let { off ->
                val min = off.totalSeconds / 60
                "%s%02d%02d".format(if (min < 0) "-" else "+", kotlin.math.abs(min) / 60, kotlin.math.abs(min) % 60)
            }
            "{d24h}" -> state.is24hMode.toString()
            // Spelled-out time (CloX numWord / timeWordParts ports).
            "{dht}" -> numWord(h12(now.hour))
            "{dh24t}" -> numWord(if (now.hour == 0) 24 else now.hour)
            "{dmat}" -> timeWordFull(now.minute)
            "{dmtt}" -> timeWordParts(now.minute).first
            "{dmot}" -> timeWordParts(now.minute).second
            "{dsat}" -> timeWordFull(now.second)
            "{dstt}" -> timeWordParts(now.second).first
            "{dsot}" -> timeWordParts(now.second).second
            "{c_elapsed}" -> String.format("%.3f", state.elapsedSeconds)

            "{dhutc12}" -> java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).let { it.hour % 12 }.let { if (it == 0) 12 else it }.toString()
            "{dhutc12z}" -> java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).let { it.hour % 12 }.let { if (it == 0) 12 else it }.toString().padStart(2, '0')
            "{dhutc24}" -> java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).hour.toString()
            "{dhutc24z}" -> java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).hour.toString().padStart(2, '0')

            "{drs}" -> (now.second * 6).toString()
            "{drss}" -> (now.second * 6 + now.nano / 1000000000f * 6).toString()
            "{drm}" -> (now.minute * 6 + now.second * 0.1f).toString()
            "{drms}" -> String.format("%.1f", now.nano / 1_000_000_000.0 * 360)
            "{drh}" -> ((now.hour % 12) * 30 + now.minute * 0.5f).toString()
            "{drh0}" -> ((now.hour % 12) * 30).toString()
            "{drh24}" -> (now.hour * 15 + now.minute * 0.25f).toString()

            "{dd}" -> now.dayOfMonth.toString()
            "{ddz}" -> now.dayOfMonth.toString().padStart(2, '0')
            "{ddy}" -> now.dayOfYear.toString()
            "{ddim}" -> java.time.YearMonth.from(now).lengthOfMonth().toString()
            "{ddw}" -> now.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{ddw1}" -> now.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.US)
            "{ddw2}" -> now.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).take(2)
            "{ddww}" -> now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
            "{ddw0}" -> (now.dayOfWeek.value % 7).toString() // Sunday=0
            "{ddw_1}" -> now.dayOfWeek.plus(1).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{ddw_2}" -> now.dayOfWeek.plus(2).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{ddw_3}" -> now.dayOfWeek.plus(3).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{ddw_4}" -> now.dayOfWeek.plus(4).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{ddw_5}" -> now.dayOfWeek.plus(5).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{ddw_6}" -> now.dayOfWeek.plus(6).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{ddww_1}" -> now.dayOfWeek.plus(1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
            "{ddww_2}" -> now.dayOfWeek.plus(2).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
            "{dn}" -> now.monthValue.toString()
            "{dnn}" -> now.monthValue.toString().padStart(2, '0')
            "{dnnn}" -> now.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            "{dnnnn}" -> now.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
            "{dy}" -> (now.year % 100).toString().padStart(2, '0')
            "{dyy}" -> now.year.toString()
            // CloX {dw}: os.date("%W") + 1 — week 1 starts on the first Monday
            // of the year; days before it belong to week 0 ("%W" → 1..54).
            "{dw}" -> {
                val jan1 = java.time.LocalDate.of(now.year, 1, 1)
                val jan1Dow = jan1.dayOfWeek.value            // 1=Mon..7=Sun
                val firstMonday = ((8 - jan1Dow) % 7) + 1     // day-of-year of 1st Monday
                val w = if (now.dayOfYear < firstMonday) 0 else (now.dayOfYear - firstMonday) / 7
                (w + 1).toString()
            }
            // CloX {dwm}: calendar week-of-month, weeks start Sunday, week 1
            // is the week containing the 1st — NOT ceil(day/7).
            "{dwm}" -> {
                val wday0 = now.dayOfWeek.value % 7           // 0=Sunday
                val firstW = ((wday0 - (now.dayOfMonth - 1)) % 7 + 7) % 7
                ((now.dayOfMonth - 1 + firstW) / 7 + 1).toString()
            }
            "{dh23tt}" -> (now.hour / 10).toString()
            "{dh23to}" -> (now.hour % 10).toString()
            "{dhtt}" -> (now.hour % 12).let { if (it == 0) 12 else it }.let { it / 10 }.toString()
            "{dhto}" -> (now.hour % 12).let { if (it == 0) 12 else it }.let { it % 10 }.toString()
            "{dh11tt}" -> ((now.hour % 12) / 10).toString()
            "{dh11to}" -> ((now.hour % 12) % 10).toString()
            "{dh24tt}" -> (now.hour.let { if (it == 0) 24 else it } / 10).toString()
            "{dh24to}" -> (now.hour.let { if (it == 0) 24 else it } % 10).toString()
            "{dmt}" -> (now.minute / 10).toString()
            "{dmo}" -> (now.minute % 10).toString()
            "{dst}" -> (now.second / 10).toString()
            "{dso}" -> (now.second % 10).toString()
            "{ddiy}" -> if (java.time.Year.of(now.year).isLeap) "366" else "365"

            "{bl}" -> state.batteryPercentage.toString()
            "{blp}" -> state.batteryPercentage.toString() + "%"
            "{br}" -> (state.batteryPercentage * 3.6f).toString()
            "{bc}" -> if (state.batteryCharging) "Charging" else "Discharging"
            "{bv}" -> state.batteryVoltage.toString()
            "{bi}" -> state.batteryCurrentMa.toString()
            "{btc}" -> state.batteryTempC.toString()
            "{btf}" -> cToF(state.batteryTempC).toString()
            "{btcd}" -> "${state.batteryTempC}°C"
            "{btfd}" -> "${cToF(state.batteryTempC)}°F"
            "{pbl}" -> state.phoneBatteryPercentage.toString()
            "{pblp}" -> state.phoneBatteryPercentage.toString() + "%"
            "{pbr}" -> (state.phoneBatteryPercentage * 3.6f).toString()
            "{pbc}" -> if (state.phoneCharging) "Charging" else "Discharging"
            "{pbtc}" -> state.phoneBatteryTempC.toString()
            "{pbtf}" -> cToF(state.phoneBatteryTempC).toString()
            "{pbtcd}" -> "${state.phoneBatteryTempC}°C"
            "{pbtfd}" -> "${cToF(state.phoneBatteryTempC)}°F"

            "{ssc}" -> state.steps.toString()
            "{sscp}" -> pct(state.steps, state.stepGoal).toString()
            "{stsc}" -> state.stepGoal.toString()
            "{shr}" -> state.heartRate.toString()
            "{shrp}" -> pct(state.heartRate, state.maxHeartRate).toString()
            "{sthr}" -> state.maxHeartRate.toString()
            "{shr_1}" -> shrHistory(1, state)
            "{shr_2}" -> shrHistory(2, state)
            "{shr_3}" -> shrHistory(3, state)
            "{shr_4}" -> shrHistory(4, state)
            "{shr_5}" -> shrHistory(5, state)
            "{shr_6}" -> shrHistory(6, state)
            "{shr_7}" -> shrHistory(7, state)
            "{shr_8}" -> shrHistory(8, state)
            "{shr_9}" -> shrHistory(9, state)
            "{sstr}" -> state.stressScore.toString()
            "{scal}" -> state.calories.toString()
            "{scalp}" -> pct(state.calories, state.calorieGoal).toString()
            "{stcal}" -> state.calorieGoal.toString()
            "{sdst}" -> String.format("%.1f", state.distanceKm)
            "{sdstp}" -> pct(state.distanceKm, state.distanceGoalKm).toString()
            "{sdstu}" -> state.distanceUnit
            "{stdst}" -> if (state.distanceGoalKm % 1f == 0f) state.distanceGoalKm.toInt().toString()
                         else String.format("%.1f", state.distanceGoalKm)
            "{sbo}" -> state.bloodOxygen.toString()
            "{hfc}" -> state.flightsClimbed.toString()
            "{ham}" -> state.foodCalories.toString()
            "{htam}" -> state.foodCalorieGoal.toString()
            "{hae}" -> state.exerciseMinutes.toString()
            "{htae}" -> state.exerciseGoalMinutes.toString()
            "{has}" -> state.standHours.toString()
            "{htas}" -> state.standGoal.toString()

            // Sleep — CloX/BHT 2.0 extension (last night's session). Times are
            // "--:--" and durations 0 when no viable session exists.
            "{sslm}" -> state.sleepAsleepMin.toString()
            "{sslh}" -> String.format("%.1f", state.sleepAsleepMin / 60.0)
            "{sslt}" -> "%d:%02d".format(state.sleepAsleepMin / 60, state.sleepAsleepMin % 60)
            "{sslbt}" -> if (state.sleepHasData) state.sleepBedtime else "--:--"
            "{sslwt}" -> if (state.sleepHasData) state.sleepWakeTime else "--:--"
            "{sslrm}" -> state.sleepRemMin.toString()
            "{ssllm}" -> state.sleepLightMin.toString()
            "{ssldm}" -> state.sleepDeepMin.toString()
            "{sslam}" -> state.sleepAwakeMin.toString()
            "{sslp}" -> pct(state.sleepAsleepMin, state.sleepSessionMin).toString()

            "{wt}" -> state.temp.toString()
            "{wtd}" -> "${state.temp}°${state.tempUnit}"
            "{wth}" -> state.tempHigh.toString()
            "{wthd}" -> "${state.tempHigh}°${state.tempUnit}"
            "{wtl}" -> state.tempLow.toString()
            "{wtld}" -> "${state.tempLow}°${state.tempUnit}"
            "{wm}" -> state.tempUnit
            "{wh}" -> state.humidity.toString()
            "{whp}" -> "${state.humidity}%"
            "{wci}" -> state.conditionIcon
            "{wct}" -> state.conditionText
            "{wwd}" -> state.windDir.toString()
            "{wwdb}" -> bearing2(state.windDir)
            "{wwdbb}" -> bearing3(state.windDir)
            "{wws}" -> state.windSpeed.toString()
            // CloX {wcl}: cloud cover is a 0-1 FRACTION, not a percent — faces
            // compare `{wcl} >= 0.5`. cloudCover is stored 0-100.
            "{wcl}" -> fmtNum(state.cloudCover / 100.0)
            "{wp}" -> state.pressure.toString()
            "{wr}" -> state.precipitation.toString()
            "{wl}", "{wml}" -> state.weatherLocation
            "{wsr}" -> state.sunrise
            "{wss}" -> state.sunset
            "{wsrp}" -> fracOfDay(state.sunrise)
            "{wssp}" -> fracOfDay(state.sunset)
            "{wmp}" -> state.moonPhase.toString()
            "{wisday}" -> isDaytime(now, state.sunrise, state.sunset).toString()
            "{wf0dt}" -> state.temp.toString()
            "{wf0dth}" -> state.tempHigh.toString()
            "{wf0dtl}" -> state.tempLow.toString()
            "{wf0dci}" -> state.conditionIcon
            "{wf0dct}" -> state.conditionText

            "{sax}" -> String.format("%.2f", state.acceleration.first)
            "{say}" -> String.format("%.2f", state.acceleration.second)
            "{saz}" -> String.format("%.2f", state.acceleration.third)
            "{sgx}" -> String.format("%.2f", state.gyro.first)
            "{sgy}" -> String.format("%.2f", state.gyro.second)
            "{sgz}" -> String.format("%.2f", state.gyro.third)
            // CloX {scr}: raw heading is NEGATIVE (full precision) so a compass
            // needle rotated by {scr} turns opposite the bearing; {sct} is the
            // positive rounded degrees.
            "{scr}" -> (-state.compassHeading).toString()
            "{sct}" -> state.compassHeading.toString()
            "{sctd}" -> "${state.compassHeading}°"
            "{sctdb}" -> "${state.compassHeading}° ${bearing2(state.compassHeading)}"
            "{sctdbb}" -> "${state.compassHeading}° ${bearing3(state.compassHeading)}"
            "{scb}" -> bearing2(state.compassHeading)
            "{scbb}" -> bearing3(state.compassHeading)
            "{alat}" -> state.latitude.toString()
            "{alon}" -> state.longitude.toString()
            "{alatd}" -> String.format("%.1f", Math.abs(state.latitude))
            "{alond}" -> String.format("%.1f", Math.abs(state.longitude))
            "{alatdd}" -> "${String.format("%.1f", Math.abs(state.latitude))}°${if (state.latitude >= 0) "N" else "S"}"
            "{alondd}" -> "${String.format("%.1f", Math.abs(state.longitude))}°${if (state.longitude >= 0) "E" else "W"}"
            "{aalt}" -> state.altitudeM.toString()

            // Network / connectivity — derived from the source flags/signals.
            "{pwc}" -> if (state.wifiConnected) "true" else "false"
            "{pws}" -> fmtNum(state.wifiSignalBars / 4.0)          // 0..1 fraction
            "{nc}" -> if (state.wifiConnected || state.cellularConnected) "true" else "false"
            "{ncc}" -> if (state.cellularConnected) "true" else "false"
            "{ncs}" -> fmtNum(state.cellularSignalBars / 4.0)      // 0..1 fraction
            "{nbc}" -> if (state.bluetoothEnabled) "true" else "false"
            "{ngc}" -> if (state.gpsEnabled) "true" else "false"
            "{nwip}" -> state.wifiIp
            "{cnc}" -> state.notificationCount.toString()
            "{cn1a}" -> state.notificationAppName
            "{cn1t}" -> state.notificationTitle
            "{cn1c}" -> state.notificationContent
            "{abright}" -> if (state.isBright) "true" else "false"
            "{adark}" -> if (state.darkMode) "true" else "false"
            "{avol}" -> state.volume.toString()
            "{alowpw}" -> if (state.lowPower) "true" else "false"
            "{ucolor}" -> state.accentColor1
            "{ucolor2}" -> state.accentColor2
            "{ucolor3}" -> state.accentColor3
            "{ucolor_b}" -> brightenColor(state.accentColor1)
            "{ulver}" -> state.launcherVersion
            "{aosv}" -> state.osVersion   // Android RELEASE version (not the API level)
            "{aos}" -> "Android"
            "{around}" -> state.isRound.toString()
            "{atyre}" -> state.hasCutout.toString()
            "{awname}" -> state.watchName
            "{aname}" -> state.deviceName
            "{amodel}" -> state.deviceModel
            "{aman}" -> state.deviceManufacturer
            "{adimlo}" -> state.isDimLoBit.toString()
            "{abrt}" -> state.screenBrightness.toString()
            "{abtc}" -> if (state.bluetoothEnabled) "true" else "false"
            "{areboot}" -> state.lastReboot
            "{alangcode}" -> state.langCode
            "{alangreg}" -> state.langRegion
            "{alangfull}" -> state.langFull
            // Time since bright mode: ms, -1 while dim (CloX {abss}/{abssl}).
            "{abss}" -> if (state.isBright) (state.elapsedSeconds * 1000).toLong().toString() else "-1"
            "{abssl}" -> if (state.isBright) {
                if (state.elapsedSeconds > 30) "30"
                else String.format("%.3f", state.elapsedSeconds)
            } else "-1"
            "{aloc}" -> state.weatherLocation

            // CloX stopwatch tags are ZERO-PADDED strings (h/m/s → "00",
            // centis → "00", millis → "000") so `'{swm}:{sws}'` reads "00:00".
            "{swr}" -> state.stopwatchRunning.toString()
            "{swh}" -> swZ(swParts(state.stopwatchMs)[0])
            "{swm}" -> swZ(swParts(state.stopwatchMs)[1])
            "{sws}" -> swZ(swParts(state.stopwatchMs)[2])
            "{swss}" -> swZ(swParts(state.stopwatchMs)[3] / 10)
            "{swsss}" -> swZ3(swParts(state.stopwatchMs)[3])
            "{swsst}" -> state.stopwatchMs.toString()
            "{swrm}" -> (swParts(state.stopwatchMs)[1] * 6.0).toString()
            "{swrs}" -> (swParts(state.stopwatchMs)[2] * 6.0).toString()
            "{swrss}" -> (swParts(state.stopwatchMs)[2] * 6.0 + 6.0 * (state.stopwatchMs % 1000) / 1000.0).toString()
            // UL-only aliases — same values (WM leaves them empty; preview shows them).
            "{tswh}" -> swZ(swParts(state.stopwatchMs)[0])
            "{tswm}" -> swZ(swParts(state.stopwatchMs)[1])
            "{tsws}" -> swZ(swParts(state.stopwatchMs)[2])
            "{tswss}" -> swZ(swParts(state.stopwatchMs)[3] / 10)
            "{tswsss}" -> swZ3(swParts(state.stopwatchMs)[3])
            "{tswsst}" -> state.stopwatchMs.toString()

            // Alarm — all three derived from one source `alarmTime` ("HH:MM" or null).
            "{aalo}" -> if (state.alarmTime != null) "1" else "0"
            "{aalh23z}" -> state.alarmTime?.split(":")?.getOrNull(0)?.toIntOrNull()?.toString()?.padStart(2, '0') ?: "--"
            "{aalmz}" -> state.alarmTime?.split(":")?.getOrNull(1)?.toIntOrNull()?.toString()?.padStart(2, '0') ?: "--"

            // Memory / storage — every variant derived from used/total (GB), so
            // used+free = total and the percentages are consistent (CloX storage defs).
            "{amu}" -> gb1(state.usedRamGb)
            "{amuf}" -> gb1(state.usedRamGb) + " GB"
            "{amt}" -> gb1(state.totalRamGb)
            "{amtf}" -> gb1(state.totalRamGb) + " GB"
            "{amf}" -> gb1(state.totalRamGb - state.usedRamGb)
            "{amff}" -> gb1(state.totalRamGb - state.usedRamGb) + " GB"
            "{amup}" -> pct1(state.usedRamGb, state.totalRamGb)
            "{amfp}" -> pct1(state.totalRamGb - state.usedRamGb, state.totalRamGb)
            "{adsu}" -> gb1(state.usedStorageGb)
            "{adsuf}" -> gb1(state.usedStorageGb) + " GB"
            "{adst}" -> gb1(state.totalStorageGb)
            "{adstf}" -> gb1(state.totalStorageGb) + " GB"
            "{adsf}" -> gb1(state.totalStorageGb - state.usedStorageGb)
            "{adsff}" -> gb1(state.totalStorageGb - state.usedStorageGb) + " GB"
            "{adsup}" -> pct1(state.usedStorageGb, state.totalStorageGb)
            "{adsfp}" -> pct1(state.totalStorageGb - state.usedStorageGb, state.totalStorageGb)

            "{sprs}" -> state.pressure.toString()
            "{wlu}" -> String.format("%02d:%02d:%02d", now.hour, now.minute, now.second)
            "{aw3w}" -> "filed.shot.spar"
            "{aw3w1}" -> "filed"
            "{aw3w2}" -> "shot"
            "{aw3w3}" -> "spar"

            "{cex}" -> if (calEvents.isNotEmpty()) "true" else "false"

            else -> resolveDynamic(tag, state)
                ?: resolveVariable(tag, state)
                ?: sampleValues[tag]
                ?: tag
        }
    }

    // Variables currently being resolved on this thread — guards against cyclic
    // references (e.g. a = {b}, b = {a}) sending resolveTag into infinite recursion.
    private val resolvingVars = ThreadLocal.withInitial { HashSet<String>() }

    /** The document owns its VM even when its initial global script is blank or failed. */
    private fun evalLua(expr: String, state: WmPreviewState): String {
        val rt = state.scriptRuntime
        return if (rt != null) rt.eval(expr)
        else WmExpressionEval.eval(expr, state.variables)
    }

    /** Resolves a `{name}` tag against the global-script variables in [state].
     * The variable's raw RHS is itself evaluated (tags substituted, then run through
     * [WmExpressionEval]) so a variable can reference other tags / variables and
     * still animate. Returns null when [tag] names no known variable.
     */
    private fun resolveVariable(tag: String, state: WmPreviewState): String? {
        if (tag.length < 3 || tag[0] != '{' || tag[tag.length - 1] != '}') return null
        val name = tag.substring(1, tag.length - 1)
        // Live script runtime is authoritative for variable reads — the value is
        // already final (re-evaluating it would mangle e.g. "0000011000000000").
        state.scriptRuntime?.let { rt ->
            if (rt.hasScript) {
                rt.value(name)?.let { return it }
                if (rt.has(name)) return ""   // exists but non-scalar (table/function)
            }
        }
        if (state.variables.isEmpty()) return null
        val raw = state.variables[name] ?: return null
        val active = resolvingVars.get()!!
        if (!active.add(name)) return ""   // cycle — bail with empty
        return try {
            val substituted = evalPattern.replace(raw) { m -> resolveTag(m.value, state) }
            evalLua(substituted, state)
        } finally {
            active.remove(name)
        }
    }

    // Assignment `name = rhs`. The `(?!=)` keeps comparisons (`x == 1`) from being
    // mistaken for an assignment to `x`. `local` prefix is tolerated.
    private val reAssign = Regex("""^(?:local\s+)?([A-Za-z_]\w*)\s*=(?!=)\s*(.+)$""")
    // Lua block openers / closers, used to track nesting so only TOP-LEVEL (depth 0)
    // assignments are treated as globals. `elseif` is safely ignored (`\bif\b` needs a
    // word boundary, and "elseif" has none before "if"); `do` is not counted because it
    // pairs with for/while, whose opener already incremented the depth.
    private val reBlockOpen = Regex("""\b(function|if|for|while|repeat)\b""")
    private val reBlockClose = Regex("""\b(end|until)\b""")

    /**
     * Extracts top-level variable assignments from a WatchMaker global script
     * (`scripts/script.txt`), keyed by name → raw RHS expression. Lua comments are
     * stripped and block bodies (functions, if/for/while) are skipped via depth
     * tracking, so locals reassigned inside callbacks (`var_park = 1` in `park()`)
     * don't shadow the top-level default. The first assignment to a name wins (the
     * top-level defaults appear before the functions that mutate them), guarding
     * against any nesting miscount. The RHS is kept verbatim; bare-identifier reads
     * in expressions are resolved by [WmExpressionEval], braced `{name}` reads by
     * [resolveVariable].
     */
    fun parseGlobalVariables(script: String): Map<String, String> {
        if (script.isBlank()) return emptyMap()
        val vars = LinkedHashMap<String, String>()
        var depth = 0
        for (rawLine in script.lines()) {
            val line = stripLuaComment(rawLine).trim()
            if (line.isEmpty()) continue
            if (depth == 0) {
                reAssign.matchEntire(line)?.let { m ->
                    val name = m.groupValues[1]
                    val rhs = m.groupValues[2].trim()
                    // Skip function values and don't override an earlier default.
                    if (!rhs.startsWith("function") && !vars.containsKey(name)) {
                        vars[name] = rhs
                    }
                }
            }
            val opens = reBlockOpen.findAll(line).count()
            val closes = reBlockClose.findAll(line).count()
            depth = (depth + opens - closes).coerceAtLeast(0)
        }
        return vars
    }

    /** Drops a trailing Lua `--` comment, leaving any `--` that sits inside a string. */
    private fun stripLuaComment(line: String): String {
        var inStr = false
        var quote = ' '
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            if (inStr) {
                if (c == quote) inStr = false
            } else when {
                c == '"' || c == '\'' -> { inStr = true; quote = c }
                c == '-' && line[i + 1] == '-' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private val sampleValues: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        categories.values.flatten().forEach { map[it.tag] = it.sample }
        map
    }

    private val sortedTags: List<String> by lazy {
        sampleValues.keys.sortedByDescending { it.length }
    }

    private val evalPattern = Regex("\\{([^{}]+)\\}")
    // Hoisted so evaluatePreview (called per tagged attr per layer per preview tick) does not
    // allocate a new Regex on every call for quote-stripping.
    private val quotePattern = Regex("'([^']*)'")

    fun evaluatePreview(expression: String, state: WmPreviewState = WmPreviewState()): String {
        if (expression.isBlank()) return ""

        // Fast path for simple strings (like colors "ffffff" or size "100")
        // If there are no '{' tags, no math operators, and no global-script variable
        // names appear, return immediately.
        val hasTags = expression.contains('{')
        val hasMath = expression.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '%' || it == '(' || it == ')' || it == '>' || it == '<' }
        // WatchMaker faces use variables as bare identifiers (e.g. `var_face == 0 ...`),
        // so an expression mentioning any known variable must go through the evaluator.
        val hasVar = state.variables.isNotEmpty() && state.variables.keys.any { expression.contains(it) }
        if (!hasTags && !hasMath && !hasVar) {
            // Still strip WM string-literal quotes — a plain literal like ':'
            // takes this fast path and the quotes must not render
            return if (expression.contains('\'')) {
                expression.replace(quotePattern, "$1")
            } else expression
        }

        var result = expression

        if (hasTags) {
            // Step 1: substitute known literal tags directly via Regex in one pass.
            // This replaces the extremely slow O(150) containing loop.
            result = evalPattern.replace(result) { match ->
                val tag = match.value
                resolveTag(tag, state)
            }

            // Step 2: evaluate any remaining {expr} patterns (up to 3 passes)
            repeat(3) {
                val prev = result
                result = evalPattern.replace(result) { m ->
                    val inner = m.groupValues[1]
                    val evaluated = evalLua(inner, state)
                    if (evaluated != inner) evaluated else m.value
                }
                if (result == prev) return@repeat
            }
        }

        // Step 3: final full-expression eval — ONLY if result still looks like an
        // expression (i.e., no resolved string with leading zeros that we must preserve).
        // A result "looks like a plain value" if it has no operator/function chars outside
        // of digits, dots, colons, slashes, spaces, and common display chars.
        // Check for both spaced (and or) and non-spaced (and/or) variants
        val hasAndOr = result.contains("and") || result.contains("or")
        val looksLikeMathExpr = result.isNotEmpty() &&
            !result.contains('{') &&
            (hasAndOr || hasVar || result.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '%' ||
                         it == '(' || it == ')' || it == '>' || it == '<' })
        if (looksLikeMathExpr) {
            val fullEval = evalLua(result, state)
            if (fullEval != result) result = fullEval
        }

        // Strip WM string-literal quotes: '...' prevents math evaluation but the
        // quotes themselves are not rendered. Tags inside are already substituted above.
        if (result.contains('\'')) {
            result = result.replace(quotePattern, "$1")
        }

        return result
    }
}
