package com.ailife.wftools.pc

/** 搜索深度：数值越低越省额度，越深召回越好。 */
enum class SearchDepth(val wire: String, val label: String, val description: String) {
    BASIC("basic", "快速检索", "一次检索，速度快、额度消耗低，适合常规查询。"),
    ADVANCED("advanced", "深度检索", "多轮检索与更完整的片段，结果更好但耗时和额度更高。"),
    ;

    companion object {
        /** 解析设置值；未知输入回落到 [BASIC]。 */
        fun parse(raw: String?): SearchDepth = entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: BASIC
    }
}

/** Tavily `max_results` 的合法范围与默认值。 */
object SearchResultCount {
    const val MIN = 1
    const val MAX = 20
    const val DEFAULT = 5

    /** 把任意整数收进 [MIN]..[MAX]。 */
    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    /** 解析用户输入；非法输入回落到 [DEFAULT]。 */
    fun parse(raw: String): Int = raw.trim().toIntOrNull()?.let(::clamp) ?: DEFAULT

    /** 输入是否落在可接受的范围内；用于给输入框标记错误态。 */
    fun isInRange(raw: String): Boolean = raw.trim().toIntOrNull()?.let { it in MIN..MAX } == true
}
