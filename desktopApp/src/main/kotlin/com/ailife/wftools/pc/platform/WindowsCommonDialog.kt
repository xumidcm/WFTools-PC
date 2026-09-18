package com.ailife.wftools.pc.platform

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.PointerByReference
import java.awt.Component
import java.lang.ref.Reference
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/**
 * Vista+ common item dialog (`IFileOpenDialog` / `IFileSaveDialog`).
 *
 * AWT's [java.awt.FileDialog] maps to the legacy `GetOpenFileName` on Windows, which is why
 * the picker looked dated, ignored its icon list and silently dropped the filename filter.
 * The common item dialog is the modern shell dialog: it inherits the process icon, honours
 * file-type filters, and supports multi-selection and folder picking.
 *
 * The dialog has no IDispatch interface, so the vtable is called directly. Everything here is
 * best-effort: failures before the shell dialog is shown can fall back to AWT. Once the user has
 * seen and dismissed the modern picker, its empty/partial result is final; opening a second legacy
 * picker would make one click unexpectedly launch two different file choosers.
 */
internal sealed class ModernDialogResult {
    data class Completed(val paths: List<Path>) : ModernDialogResult()
    data object Unavailable : ModernDialogResult()
}

internal object WindowsCommonDialog {
    private val CLSID_FILE_OPEN_DIALOG = GUID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}")
    private val CLSID_FILE_SAVE_DIALOG = GUID("{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}")
    private val IID_FILE_OPEN_DIALOG = GUID("{d57c7288-d4ad-4768-be02-9d969532d960}")
    private val IID_FILE_SAVE_DIALOG = GUID("{84bccd23-5fde-4cdb-aea4-af64b83d78ab}")

    private const val CLSCTX_INPROC_SERVER = 1
    private const val S_OK = 0
    private const val S_FALSE = 1
    private val HRESULT_CANCELLED = 0x800704C7.toInt()
    private val dialogThreadNumber = AtomicInteger(1)

    // IFileDialog::SetOptions flags.
    private const val FOS_OVERWRITEPROMPT = 0x00000002
    private const val FOS_NOCHANGEDIR = 0x00000008
    private const val FOS_FORCEFILESYSTEM = 0x00000040
    private const val FOS_ALLOWMULTISELECT = 0x00000200
    private const val FOS_PATHMUSTEXIST = 0x00000800
    private const val FOS_FILEMUSTEXIST = 0x00001000

    /** SIGDN_FILESYSPATH — ask IShellItem for a real file-system path. */
    private const val SIGDN_FILESYSPATH = 0x80058000

    // IFileDialog vtable slots, counted after the three IUnknown entries.
    private const val SLOT_RELEASE = 2
    private const val SLOT_SHOW = 3
    private const val SLOT_SET_FILE_TYPES = 4
    private const val SLOT_SET_OPTIONS = 9
    private const val SLOT_GET_OPTIONS = 10
    private const val SLOT_SET_FILENAME = 15
    private const val SLOT_SET_TITLE = 17
    private const val SLOT_GET_RESULT = 20
    private const val SLOT_SET_DEFAULT_EXTENSION = 22
    /** IFileOpenDialog adds GetResults directly after IFileDialog's last method. */
    private const val SLOT_GET_RESULTS = 27

    // IShellItem
    private const val SLOT_ITEM_GET_DISPLAY_NAME = 5
    // IShellItemArray
    private const val SLOT_ARRAY_GET_COUNT = 7
    private const val SLOT_ARRAY_GET_ITEM_AT = 8

    /** True when the modern dialog can be attempted at all. */
    val usable: Boolean get() = desktopOperatingSystem() == DesktopOperatingSystem.WINDOWS
    @Volatile internal var lastApartmentThreadName: String? = null
        private set
    @Volatile internal var lastFailure: String? = null
        private set

    /**
     * Byte offset of [slot] within a COM vtable. The table holds function pointers, so the slot
     * index has to be scaled by the pointer size; using the bare index would read past the end
     * of the table on 64-bit builds.
     */
    internal fun vtableOffset(slot: Int, pointerSize: Int = Native.POINTER_SIZE): Long {
        require(slot >= 0) { "vtable slot must not be negative" }
        require(pointerSize > 0) { "pointer size must be positive" }
        return slot.toLong() * pointerSize
    }

    /**
     * Runs the dialog. [multiple] allows multi-select and [save] switches to the save flavour
     * with an overwrite prompt. The nullable wrapper is retained for callers that only need the
     * old API; [showWithOutcome] carries the important distinction between cancel and unavailable.
     */
    fun show(
        owner: Component?,
        title: String,
        filters: List<FileDialogFilter>,
        defaultName: String? = null,
        defaultExtension: String? = null,
        save: Boolean = false,
        multiple: Boolean = false,
    ): List<Path>? {
        return when (val outcome = showWithOutcome(owner, title, filters, defaultName, defaultExtension, save, multiple)) {
            is ModernDialogResult.Completed -> outcome.paths
            ModernDialogResult.Unavailable -> null
        }
    }

    /** Runs the modern picker without conflating a user cancellation with COM unavailability. */
    internal fun showWithOutcome(
        owner: Component?,
        title: String,
        filters: List<FileDialogFilter>,
        defaultName: String? = null,
        defaultExtension: String? = null,
        save: Boolean = false,
        multiple: Boolean = false,
    ): ModernDialogResult {
        if (!usable) return ModernDialogResult.Unavailable
        val ownerPointer = ownerHandle(owner)
        val dialogShown = java.util.concurrent.atomic.AtomicBoolean(false)
        val outcome = onStaThread {
            val open = !save
            val out = PointerByReference()
            val created = hrValue(Ole32.INSTANCE.CoCreateInstance(
                if (open) CLSID_FILE_OPEN_DIALOG else CLSID_FILE_SAVE_DIALOG,
                null,
                CLSCTX_INPROC_SERVER,
                if (open) IID_FILE_OPEN_DIALOG else IID_FILE_SAVE_DIALOG,
                out,
            ))
            if (created != S_OK) return@onStaThread unavailable("CoCreateInstance", created)
            val dialog = out.value ?: return@onStaThread unavailable("CoCreateInstance returned no dialog")
            try {
                var options = FOS_FORCEFILESYSTEM or FOS_NOCHANGEDIR
                options = if (save) options or FOS_OVERWRITEPROMPT else options or FOS_FILEMUSTEXIST or FOS_PATHMUSTEXIST
                if (multiple) options = options or FOS_ALLOWMULTISELECT
                val optionsResult = invoke(dialog, SLOT_SET_OPTIONS, options)
                if (optionsResult != S_OK) return@onStaThread unavailable("IFileDialog.SetOptions", optionsResult)
                if (title.isNotBlank()) invoke(dialog, SLOT_SET_TITLE, WString(title))
                if (filters.isNotEmpty() && !applyFileTypes(dialog, filters)) return@onStaThread ModernDialogResult.Unavailable
                defaultExtension?.takeIf(String::isNotBlank)?.let { invoke(dialog, SLOT_SET_DEFAULT_EXTENSION, WString(it)) }
                defaultName?.takeIf(String::isNotBlank)?.let { invoke(dialog, SLOT_SET_FILENAME, WString(it)) }

                val shown = invokeWithPointer(dialog, SLOT_SHOW, ownerPointer)
                // S_OK means a selection was made; the cancellation HRESULT means the user saw
                // the modern dialog and deliberately closed it. Both are terminal outcomes.
                if (shown == S_OK || shown == HRESULT_CANCELLED) dialogShown.set(true)
                if (shown == HRESULT_CANCELLED) return@onStaThread ModernDialogResult.Completed(emptyList())
                if (shown != S_OK) return@onStaThread unavailable("IFileDialog.Show", shown)

                if (!multiple) {
                    val result = PointerByReference()
                    val resultCode = invokeOut(dialog, SLOT_GET_RESULT, result)
                    if (resultCode != S_OK) {
                        unavailable("IFileDialog.GetResult", resultCode)
                        return@onStaThread ModernDialogResult.Completed(emptyList())
                    }
                    val item = result.value
                    if (item == null) {
                        unavailable("IFileDialog.GetResult returned no item")
                        return@onStaThread ModernDialogResult.Completed(emptyList())
                    }
                    ModernDialogResult.Completed(try { listOfNotNull(displayName(item)) } finally { release(item) })
                } else ModernDialogResult.Completed(readMultiple(dialog))
            } finally { release(dialog) }
        }
        // A native extraction quirk must not reopen an AWT dialog after the user already made a
        // choice. Keep the diagnostic in lastFailure, but treat the visible modern interaction as
        // complete even if an unexpected exception escaped the STA block.
        return outcome ?: if (dialogShown.get()) ModernDialogResult.Completed(emptyList()) else ModernDialogResult.Unavailable
    }

    internal fun shouldFallbackToLegacy(result: ModernDialogResult): Boolean = result is ModernDialogResult.Unavailable

    /**
     * AWT/Compose may already have initialised its event thread as MTA. IFileDialog requires STA;
     * attempting to reuse the UI thread then makes Show fail and used to silently trigger the
     * legacy AWT fallback. A short-lived dedicated apartment is deterministic on both JBR and
     * ordinary OpenJDK while the native owner HWND still preserves modality.
     */
    private fun <T> onStaThread(block: () -> T?): T? {
        lastFailure = null
        val outcome = AtomicReference<Result<T?>>()
        val thread = Thread({
            lastApartmentThreadName = Thread.currentThread().name
            val initialized = hrValue(Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED))
            if (initialized != S_OK && initialized != S_FALSE) {
                lastFailure = "CoInitializeEx failed: ${hresult(initialized)}"
                outcome.set(Result.success(null))
                return@Thread
            }
            try {
                outcome.set(runCatching(block))
            } finally {
                runCatching { Ole32.INSTANCE.CoUninitialize() }
            }
        }, "WFTools-FileDialog-STA-${dialogThreadNumber.getAndIncrement()}").apply { isDaemon = true }
        thread.start()
        try { thread.join() }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            lastFailure = "等待 Windows 文件选择器时被中断"
            return null
        }
        val result = outcome.get() ?: return unavailable("Windows 文件选择器线程未返回结果")
        result.exceptionOrNull()?.let { error -> lastFailure = error.message ?: error.javaClass.simpleName }
        return result.getOrNull()
    }

    /**
     * Builds the COMDLG_FILTERSPEC array. The label and pattern buffers must stay reachable for
     * the lifetime of the dialog, so the whole block is allocated here in one piece.
     */
    private fun applyFileTypes(dialog: Pointer, filters: List<FileDialogFilter>): Boolean {
        val pointerSize = Native.POINTER_SIZE
        val block = Memory(((filters.size + 1) * pointerSize * 2).toLong())
        // Keep every wchar buffer strongly reachable until SetFileTypes has copied the specs.
        // The old implementation only stored their native pointers, so GC could invalidate a
        // filter while the shell was constructing the dialog.
        val strings = ArrayList<Memory>(filters.size * 2)
        var offset = 0L
        filters.forEach { filter ->
            strings += wideString(filter.label)
            strings += wideString(filter.pattern)
            block.setPointer(offset, strings[strings.lastIndex - 1]); offset += pointerSize
            block.setPointer(offset, strings.last()); offset += pointerSize
        }
        block.setPointer(offset, Pointer.NULL)
        block.setPointer(offset + pointerSize, Pointer.NULL)
        val result = invoke(dialog, SLOT_SET_FILE_TYPES, filters.size, block)
        Reference.reachabilityFence(strings)
        if (result != S_OK) unavailable("IFileDialog.SetFileTypes", result)
        return result == S_OK
    }

    private fun wideString(value: String): Memory = Memory((value.length + 1) * 2L).apply { setWideString(0, value) }

    private fun readMultiple(dialog: Pointer): List<Path> {
        val array = PointerByReference()
        val result = invokeOut(dialog, SLOT_GET_RESULTS, array)
        if (result != S_OK) {
            unavailable("IFileOpenDialog.GetResults", result)
            return emptyList()
        }
        val items = array.value ?: run {
            unavailable("IFileOpenDialog.GetResults returned no array")
            return emptyList()
        }
        return try {
            val counter = Memory(4L)
            val countResult = invoke(items, SLOT_ARRAY_GET_COUNT, counter)
            if (countResult != S_OK) {
                unavailable("IShellItemArray.GetCount", countResult)
                return emptyList()
            }
            val count = counter.getInt(0L).coerceAtLeast(0)
            val paths = (0 until count).mapNotNull { index ->
                val one = PointerByReference()
                if (invokeIndexedOut(items, SLOT_ARRAY_GET_ITEM_AT, index, one) != S_OK) null
                else one.value?.let { item -> try { displayName(item) } finally { release(item) } }
            }
            if (paths.isEmpty() && count > 0) unavailable("IShellItem.GetDisplayName returned no file-system paths")
            paths
        } finally { release(items) }
    }

    private fun displayName(item: Pointer): Path? = runCatching {
        val out = PointerByReference()
        if (invoke(item, SLOT_ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, out) != S_OK) return null
        val text = out.value ?: return null
        try { text.getWideString(0)?.takeIf(String::isNotBlank)?.let(Path::of) }
        finally { runCatching { Ole32.INSTANCE.CoTaskMemFree(text) } }
    }.getOrNull()

    private fun ownerHandle(owner: Component?): Pointer? =
        owner?.let(SwingUtilities::getWindowAncestor)
            ?.let { window -> runCatching { WinDef.HWND(Native.getComponentPointer(window)).pointer }.getOrNull() }

    private fun release(instance: Pointer) { runCatching { invoke(instance, SLOT_RELEASE) } }

    /**
     * Creates a real `IFileDialog`, writes an option through the vtable and reads it back.
     *
     * The dialog exposes no type library, so the slot indices are the one part of this file
     * that cannot be checked by the compiler. This round trip proves `SetOptions`/`GetOptions`
     * resolve to the intended functions without ever showing a window.
     *
     * Returns null when the modern dialog is unavailable (non-Windows, or COM refused).
     */
    internal fun verifyOptionRoundTrip(flags: Int = FOS_FILEMUSTEXIST or FOS_FORCEFILESYSTEM): Boolean? {
        if (!usable) return null
        return onStaThread {
            val out = PointerByReference()
            val created = hrValue(Ole32.INSTANCE.CoCreateInstance(CLSID_FILE_OPEN_DIALOG, null, CLSCTX_INPROC_SERVER, IID_FILE_OPEN_DIALOG, out))
            if (created != S_OK) return@onStaThread unavailable("CoCreateInstance", created)
            val dialog = out.value ?: return@onStaThread unavailable("CoCreateInstance returned no dialog")
            try {
                if (invoke(dialog, SLOT_SET_OPTIONS, flags) != S_OK) return@onStaThread false
                val readBack = Memory(4L)
                if (invoke(dialog, SLOT_GET_OPTIONS, readBack) != S_OK) return@onStaThread false
                // The shell adds its own defaults, so the bits we set must be present rather
                // than equal the whole mask.
                readBack.getInt(0L) and flags == flags
            } finally { release(dialog) }
        }
    }

    /** HRESULT extends NativeLong, which is only a Number; normalise it for comparison. */
    private fun hrValue(result: Number): Int = result.toInt()

    private fun unavailable(step: String, code: Int? = null): Nothing? {
        lastFailure = if (code == null) step else "$step failed: ${hresult(code)}"
        return null
    }

    private fun hresult(value: Int): String = "0x${value.toUInt().toString(16).uppercase().padStart(8, '0')}"

    /**
     * Resolves one vtable slot to a callable native function. Field 0 of a COM object is the
     * vtable pointer; the function pointer lives at `slot * pointerSize` inside that table.
     */
    internal fun function(instance: Pointer, slot: Int): Function =
        Function.getFunction(instance.getPointer(0L).getPointer(vtableOffset(slot)))

    internal fun invoke(instance: Pointer, slot: Int, vararg args: Any?): Int =
        function(instance, slot).invokeInt(arrayOf<Any?>(instance, *args))

    /** IShellItemArray::GetItemAt is the one out-parameter method whose index precedes the out ptr. */
    internal fun itemAtArguments(instance: Pointer, index: Int, out: PointerByReference): Array<Any?> =
        arrayOf(instance, index, out)

    private fun invokeIndexedOut(instance: Pointer, slot: Int, index: Int, out: PointerByReference): Int =
        function(instance, slot).invokeInt(itemAtArguments(instance, index, out))

    private fun invokeOut(instance: Pointer, slot: Int, out: PointerByReference, vararg extra: Any?): Int =
        function(instance, slot).invokeInt(arrayOf<Any?>(instance, out, *extra))

    private fun invokeWithPointer(instance: Pointer, slot: Int, argument: Pointer?): Int =
        function(instance, slot).invokeInt(arrayOf<Any?>(instance, argument ?: Pointer.NULL))
}
