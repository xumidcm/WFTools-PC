package com.ailife.wftools.pc.platform

import java.awt.Window
import javax.imageio.ImageIO

/** Uses the JNA runtime already shipped with LibVLC; no global theme or registry settings are changed. */
fun applyWindowChrome(window: Window, dark: Boolean) {
    WindowChrome.icon?.let { if(window.iconImages.firstOrNull() !== it) window.iconImages = listOf(it) }
    if(!System.getProperty("os.name").startsWith("Windows",true) || !window.isDisplayable) return
    runCatching {
        val native = Class.forName("com.sun.jna.Native")
        val hwnd = native.getMethod("getComponentPointer",java.awt.Component::class.java).invoke(null,window)
        val libraryClass = Class.forName("com.sun.jna.NativeLibrary")
        val library = libraryClass.getMethod("getInstance",String::class.java).invoke(null,"dwmapi")
        val function = libraryClass.getMethod("getFunction",String::class.java).invoke(library,"DwmSetWindowAttribute")
        val memoryClass = Class.forName("com.sun.jna.Memory")
        val memory = memoryClass.getConstructor(Long::class.javaPrimitiveType).newInstance(4L)
        try {
            memoryClass.getMethod("setInt",Long::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(memory,0L,if(dark) 1 else 0)
            val invoke = function.javaClass.getMethod("invokeInt",Array<Any>::class.java)
            val result = invoke.invoke(function,arrayOf(hwnd,20,memory,4)) as Int
            if(result != 0) invoke.invoke(function,arrayOf(hwnd,19,memory,4))
        } finally { runCatching { memoryClass.getMethod("close").invoke(memory) } }
    }
}
private object WindowChrome {
    val icon by lazy { runCatching { javaClass.getResourceAsStream("/icons/wftools.png")?.use { ImageIO.read(it) } }.getOrNull() }
}
