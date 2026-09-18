package com.ailife.wftools.agent.tools

/** Curated, high-signal format notes; exhaustive references stay out of the base prompt. */
object WatchAgentKnowledgeCatalog {
    val entries = listOf(
        WatchAgentKnowledgeEntry(
            title = "WatchMaker 包与路径",
            text = "watch.xml 位于 .watch ZIP 根目录；图片、字体和脚本分别放在 images/、fonts/、scripts/script.txt。图片 path 相对于 images/，不要写 images/ 前缀；禁止绝对路径和 .. 越界。",
            source = "watchmaker-format",
        ),
        WatchAgentKnowledgeEntry(
            title = "变量标签与表达式",
            text = "动态标签遵循 WatchMaker 语义。简单算式使用快速表达式；混合表达式与普通文本要分段解析。var_s_t1..var_s_t5、var_s_v1..var_s_v5 等脚本变量合法为 nil 时，文字结果为空字符串，不要回显变量源码。",
            source = "watchmaker-runtime",
        ),
        WatchAgentKnowledgeEntry(
            title = "全局 Lua 脚本",
            text = "完整 Lua 语法（function、local、return、end）交给 Lua backend；全局脚本只写 scripts/script.txt。脚本修改后必须 validate_face，编译、运行或指令限额失败要保留错误诊断。",
            source = "watchmaker-script",
        ),
        WatchAgentKnowledgeEntry(
            title = "图层点击动作",
            text = "只有显式非空 tap_action 或 action 才登记点击区域；opacity=0 的动作热区仍可点击。点击区域跟随图层最终变换和动态尺寸，没有动作的上层图层不应遮挡下层动作。",
            source = "watchmaker-actions",
        ),
        WatchAgentKnowledgeEntry(
            title = "V1 与 Studio V2",
            text = "常规设计坐标为 512×512，原点在画布中心 (256,256)，x 向右、y 向下，文档顺序由底到顶。x/y 是相对中心的偏移，不是左上角绝对坐标。build_source=studio 等信号才表示 V2；保留 id、parentId 和 Ambient 结构，不要擅自升级 V1。",
            source = "watchmaker-format",
        ),
        WatchAgentKnowledgeEntry(
            title = "最小可渲染 watch.xml 模板",
            text = "最小可渲染模板：<Watch width=\"512\" height=\"512\"><Layers><Layer type=\"image\" path=\"bg.png\"/><Layer type=\"text\" text=\"12:34\" t_font=\"sans\" t_size=\"48\" t_color=\"FFFFFF\"/><Layer type=\"shape\" color=\"00FF00\" width=\"80\" height=\"20\"/></Layers></Watch>。元素名必须是 Layer，type 可为 image、text、shape；图片放 images/bg.png。设计坐标原点为 (256,256)，x 向右、y 向下。",
            source = "watchmaker-format",
        ),
    )
}


