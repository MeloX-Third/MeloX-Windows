package melox.platform

import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** INetworkCostManager::GetCost(NULL) queries the preferred Internet route, including VPNs. */
actual fun networkUsesMeteredConnection(): Boolean? {
    if (!Platform.isWindows()) return null
    val ole = Ole32.INSTANCE
    val initialized = ole.CoInitializeEx(null, 0).toInt()
    val ownsApartment = initialized >= 0
    // RPC_E_CHANGED_MODE means this thread already owns a different apartment.
    if (!ownsApartment && initialized != -2147417850) return null
    try {
        val output = PointerByReference()
        val result = ole.CoCreateInstance(Guid.CLSID("{DCB00C01-570F-4A9B-8D69-199FDBA5723B}"), null, 1,
            Guid.IID("{DCB00008-570F-4A9B-8D69-199FDBA5723B}"), output)
        if (result.toInt() < 0 || output.value == null) return null
        val manager = NetworkCostManager(output.value)
        try {
            val cost = IntByReference()
            if (manager.getCost(cost).toInt() < 0) return null
            return when {
                cost.value and 0xF0006 != 0 -> true // Fixed/variable/over-limit/congested/roaming/near-limit.
                cost.value and 1 != 0 -> false
                else -> null
            }
        } finally { manager.Release() }
    } catch (_: Exception) { return null }
    finally { if (ownsApartment) ole.CoUninitialize() }
}

private class NetworkCostManager(pointer: Pointer) : Unknown(pointer) {
    fun getCost(cost: IntByReference): WinNT.HRESULT =
        _invokeNativeObject(3, arrayOf(getPointer(), cost, null), WinNT.HRESULT::class.java) as WinNT.HRESULT
}
