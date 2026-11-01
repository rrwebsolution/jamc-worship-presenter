package com.jamc.worship.output;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The names Windows shows for connected screens ("EPSON PJ", "SAMSUNG", "Built-in screen"), read
 * from the monitors' EDID through the display configuration API. Java only knows "\Display0".
 */
final class MonitorNames {

    private static final Logger log = LoggerFactory.getLogger(MonitorNames.class);

    private static final int QDC_ONLY_ACTIVE_PATHS = 2;
    private static final int PATH_SIZE = 72;
    private static final int MODE_SIZE = 64;
    private static final int GET_SOURCE_NAME = 1;
    private static final int GET_TARGET_NAME = 2;
    private static final int SOURCE_NAME_SIZE = 84;
    private static final int TARGET_NAME_SIZE = 420;
    private static final int OUTPUT_INTERNAL = 0x80000000;

    /** One monitor in Windows' own order (the order Java numbers its screen devices in). */
    record Monitor(String gdiName, Rectangle bounds, String name) {
    }

    private MonitorNames() {
    }

    /** The connected monitors with their names (name null when Windows has none); empty off Windows. */
    static List<Monitor> monitors() {
        if (!Platform.isWindows()) {
            return List.of();
        }
        try {
            Map<String, String> names = friendlyNames();
            List<Monitor> out = new ArrayList<>();
            User32.INSTANCE.EnumDisplayMonitors(null, null, (hMonitor, hdc, rect, data) -> {
                WinUser.MONITORINFOEX info = new WinUser.MONITORINFOEX();
                if (User32.INSTANCE.GetMonitorInfo(hMonitor, info).booleanValue()) {
                    String gdi = new String(info.szDevice).trim().replace("\0", "");
                    out.add(new Monitor(gdi, info.rcMonitor.toRectangle(), names.get(gdi.toUpperCase())));
                }
                return 1;
            }, new WinDef.LPARAM(0));
            return out;
        } catch (Throwable e) {
            log.debug("Could not read monitor names", e);
            return List.of();
        }
    }

    /** GDI device name ("\\.\DISPLAY2", upper case) → monitor name. */
    private static Map<String, String> friendlyNames() {
        NativeLibrary user32 = NativeLibrary.getInstance("user32");
        Function sizes = user32.getFunction("GetDisplayConfigBufferSizes");
        Function query = user32.getFunction("QueryDisplayConfig");
        Function info = user32.getFunction("DisplayConfigGetDeviceInfo");
        Map<String, String> out = new HashMap<>();
        IntByReference np = new IntByReference();
        IntByReference nm = new IntByReference();
        if (sizes.invokeInt(new Object[]{QDC_ONLY_ACTIVE_PATHS, np, nm}) != 0 || np.getValue() == 0) {
            return out;
        }
        Memory paths = new Memory((long) np.getValue() * PATH_SIZE);
        Memory modes = new Memory((long) Math.max(1, nm.getValue()) * MODE_SIZE);
        if (query.invokeInt(new Object[]{QDC_ONLY_ACTIVE_PATHS, np, paths, nm, modes, Pointer.NULL}) != 0) {
            return out;
        }
        for (int i = 0; i < np.getValue(); i++) {
            long p = (long) i * PATH_SIZE;
            Memory source = header(GET_SOURCE_NAME, SOURCE_NAME_SIZE, paths.getLong(p), paths.getInt(p + 8));
            Memory target = header(GET_TARGET_NAME, TARGET_NAME_SIZE, paths.getLong(p + 20), paths.getInt(p + 28));
            if (info.invokeInt(new Object[]{source}) != 0 || info.invokeInt(new Object[]{target}) != 0) {
                continue;
            }
            String gdi = source.getWideString(20).toUpperCase();
            String name = target.getWideString(36).trim();
            if (name.isEmpty() && target.getInt(24) == OUTPUT_INTERNAL) {
                name = "Built-in screen";
            }
            if (!name.isEmpty()) {
                out.putIfAbsent(gdi, name);
            }
        }
        return out;
    }

    private static Memory header(int type, int size, long adapterId, int id) {
        Memory m = new Memory(size);
        m.clear();
        m.setInt(0, type);
        m.setInt(4, size);
        m.setLong(8, adapterId);
        m.setInt(16, id);
        return m;
    }
}
