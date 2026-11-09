package com.jamc.worship.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Window;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Minimizes ONE dialog to the Windows taskbar, with its own taskbar button, while LiveCat and the projector keep
 * running. A Java dialog belongs to (is "owned" by) the main window, and Windows minimizes owned windows only together
 * with their owner, so the dialog is detached from LiveCat for as long as it is minimized and attached again when it
 * comes back (it then stays above LiveCat as before).
 *
 * <p>Windows only. Everything here runs on the Swing thread. When anything fails, the caller falls back to the old
 * minimize (a small strip at the bottom of the screen).
 */
final class TaskbarMinimize {

    private static final Logger log = LoggerFactory.getLogger(TaskbarMinimize.class);

    private static final int GWL_EXSTYLE = -20;
    private static final int GWLP_HWNDPARENT = -8;
    private static final long WS_EX_APPWINDOW = 0x00040000L;
    private static final long WS_EX_TOOLWINDOW = 0x00000080L;
    private static final int SW_HIDE = 0;
    private static final int SW_SHOWMINNOACTIVE = 7;
    private static final int SW_SHOWNORMAL = 1;

    interface User32 extends StdCallLibrary {
        long GetWindowLongPtrW(Pointer hwnd, int index);

        long SetWindowLongPtrW(Pointer hwnd, int index, long value);

        boolean ShowWindow(Pointer hwnd, int cmd);

        boolean IsIconic(Pointer hwnd);
    }

    private static final User32 USER32 = load();

    /** What each detached dialog had before: its owner window and extended style. */
    private record Saved(long owner, long exStyle) {
    }

    private static final Map<Window, Saved> DETACHED = new WeakHashMap<>();

    private TaskbarMinimize() {
    }

    private static User32 load() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            return Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);
        } catch (Throwable t) {
            log.info("Taskbar minimize not available: {}", t.toString());
            return null;
        }
    }

    static boolean supported() {
        return USER32 != null;
    }

    private static Pointer hwnd(Window w) {
        try {
            return w.isDisplayable() ? Native.getWindowPointer(w) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Detaches the dialog, gives it a taskbar button and minimizes it there. False when that is not possible. */
    static boolean minimize(Window w) {
        // only a dialog really on screen (Windows would otherwise show a window Java never showed)
        Pointer h = USER32 == null || !w.isShowing() ? null : hwnd(w);
        if (h == null) {
            return false;
        }
        try {
            long owner = USER32.GetWindowLongPtrW(h, GWLP_HWNDPARENT);
            long ex = USER32.GetWindowLongPtrW(h, GWL_EXSTYLE);
            DETACHED.put(w, new Saved(owner, ex));
            // the taskbar notices a new button only when a window is shown: hide, change, show minimized
            USER32.ShowWindow(h, SW_HIDE);
            USER32.SetWindowLongPtrW(h, GWLP_HWNDPARENT, 0);
            USER32.SetWindowLongPtrW(h, GWL_EXSTYLE, (ex | WS_EX_APPWINDOW) & ~WS_EX_TOOLWINDOW);
            USER32.ShowWindow(h, SW_SHOWMINNOACTIVE);
            return true;
        } catch (Throwable t) {
            log.warn("Could not minimize the dialog to the taskbar", t);
            reattach(w, false);
            return false;
        }
    }

    /** True while the dialog sits minimized on the taskbar. */
    static boolean isMinimized(Window w) {
        Pointer h = USER32 == null || !DETACHED.containsKey(w) ? null : hwnd(w);
        return h != null && USER32.IsIconic(h);
    }

    static boolean isDetached(Window w) {
        return DETACHED.containsKey(w);
    }

    /**
     * Attaches the dialog to LiveCat again (above the main window, no taskbar button). {@code show} brings it back to
     * normal size (after the operator clicked its taskbar button it is already restored).
     */
    static void reattach(Window w, boolean show) {
        Saved s = DETACHED.remove(w);
        Pointer h = USER32 == null || s == null ? null : hwnd(w);
        if (h == null) {
            return;
        }
        try {
            USER32.ShowWindow(h, SW_HIDE);
            USER32.SetWindowLongPtrW(h, GWLP_HWNDPARENT, s.owner());
            USER32.SetWindowLongPtrW(h, GWL_EXSTYLE, s.exStyle());
            USER32.ShowWindow(h, SW_SHOWNORMAL);
        } catch (Throwable t) {
            log.warn("Could not attach the dialog again", t);
        }
    }
}
