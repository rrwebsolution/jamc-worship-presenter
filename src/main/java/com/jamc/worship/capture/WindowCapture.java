package com.jamc.worship.capture;

import com.jamc.worship.media.MediaFrames;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WINDOW CAPTURE sources: a live picture of one open window (Windows only). The window is found by its
 * title, and when the title changes (a browser tab, a document name) by its program. A window is only
 * captured while some output asks for its picture; capturing stops a few seconds after the last request.
 *
 * <p>Pictures come from {@code PrintWindow} with full-content rendering, so the window may be covered by
 * other windows; a minimized window has no picture until it is restored.
 */
public final class WindowCapture {

    private static final Logger log = LoggerFactory.getLogger(WindowCapture.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    /** Frames per second while a window is in use. */
    private static final int FPS = 15;
    /** A window not asked for this long stops being captured. */
    private static final long IDLE_MS = 3000;
    private static final int PW_RENDERFULLCONTENT = 2;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;

    /** An open window that can be captured. */
    public record WindowInfo(String title, String exe) {
        public String label() {
            return exe == null ? title : title + "  ·  " + exe;
        }
    }

    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();
    private static ScheduledExecutorService worker;

    private WindowCapture() {
    }

    public static boolean supported() {
        return WINDOWS;
    }

    /** Visible, titled top-level windows of other programs, for the Add Source picker. */
    public static List<WindowInfo> windows() {
        List<WindowInfo> out = new ArrayList<>();
        if (!WINDOWS) {
            return out;
        }
        int ownPid = (int) ProcessHandle.current().pid();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (isCandidate(hwnd) && pid(hwnd) != ownPid) {
                out.add(new WindowInfo(title(hwnd), exe(hwnd)));
            }
            return true;
        }, null);
        return out;
    }

    /**
     * The latest picture of this window, or null while there is none yet (window closed, minimized, or the
     * first frame still coming). Calling it keeps the window captured.
     */
    public static BufferedImage frame(String title, String exe) {
        if (!WINDOWS || title == null || title.isBlank()) {
            return null;
        }
        Target t = TARGETS.computeIfAbsent(title + "\u0000" + (exe == null ? "" : exe), k -> new Target(title, exe));
        t.lastAsked = System.currentTimeMillis();
        ensureWorker();
        return t.frame;
    }

    /** Why there is no picture ("Window not found", "Window minimized"), or null when it is being captured. */
    public static String status(String title, String exe) {
        Target t = TARGETS.get(title + "\u0000" + (exe == null ? "" : exe));
        return t == null ? null : t.status;
    }

    private static synchronized void ensureWorker() {
        if (worker == null) {
            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "window-capture");
                th.setDaemon(true);
                return th;
            });
            worker.scheduleWithFixedDelay(WindowCapture::tick, 0, 1000 / FPS, TimeUnit.MILLISECONDS);
        }
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Target t : TARGETS.values()) {
            if (now - t.lastAsked > IDLE_MS) {
                TARGETS.values().remove(t); // nothing shows it any more
                continue;
            }
            try {
                changed |= t.capture(now);
            } catch (RuntimeException | Error e) {
                t.status = "Capture failed";
                log.debug("Window capture failed for {}", t.title, e);
            }
        }
        if (changed) {
            MediaFrames.frameArrived();
        }
    }

    // ------------------------------------------------------------------ one captured window

    private static final class Target {
        final String title;
        final String exe;
        volatile long lastAsked;
        volatile BufferedImage frame;
        volatile String status = "Looking for the window…";
        HWND hwnd;
        long lastLookup;
        BufferedImage spare;

        Target(String title, String exe) {
            this.title = title;
            this.exe = exe;
        }

        boolean capture(long now) {
            if (hwnd == null || !User32.INSTANCE.IsWindow(hwnd) || now - lastLookup > 2000) {
                lastLookup = now;
                HWND found = find(title, exe);
                if (found == null) {
                    hwnd = null;
                    status = "Window not found: open \"" + title + "\"";
                    frame = null;
                    return true;
                }
                hwnd = found;
            }
            if (Extra.INSTANCE.IsIconic(hwnd)) {
                status = "Window minimized: restore it to show it";
                return false; // keep the last picture
            }
            BufferedImage img = grab(hwnd, spare);
            if (img == null) {
                status = "The window could not be captured";
                return false;
            }
            spare = frame;
            frame = img;
            status = null;
            return true;
        }
    }

    /** The window with this title; else a window of the same program whose title changed. */
    static HWND find(String title, String exe) {
        HWND[] exact = new HWND[1];
        HWND[] sameProgram = new HWND[1];
        HWND[] similar = new HWND[1];
        String lower = title.toLowerCase(Locale.ROOT);
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!isCandidate(hwnd)) {
                return true;
            }
            String t = title(hwnd);
            if (t.equals(title)) {
                exact[0] = hwnd;
                return false;
            }
            boolean program = exe != null && exe.equalsIgnoreCase(exe(hwnd));
            if (program && similar[0] == null && (t.toLowerCase(Locale.ROOT).contains(lower) || lower.contains(t.toLowerCase(Locale.ROOT)))) {
                similar[0] = hwnd;
            }
            if (program && sameProgram[0] == null) {
                sameProgram[0] = hwnd;
            }
            return true;
        }, null);
        return exact[0] != null ? exact[0] : similar[0] != null ? similar[0] : sameProgram[0];
    }

    private static boolean isCandidate(HWND hwnd) {
        if (!User32.INSTANCE.IsWindowVisible(hwnd) || User32.INSTANCE.GetWindowTextLength(hwnd) == 0) {
            return false;
        }
        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        if ((exStyle & 0x00000080 /* WS_EX_TOOLWINDOW */) != 0) {
            return false; // tool palettes, tray helpers
        }
        IntByReference cloaked = new IntByReference();
        if (Dwm.INSTANCE != null && Dwm.INSTANCE.DwmGetWindowAttribute(hwnd, 14 /* DWMWA_CLOAKED */, cloaked.getPointer(), 4) == 0
                && cloaked.getValue() != 0) {
            return false; // hidden UWP / other-desktop windows
        }
        String t = title(hwnd);
        return !t.equals("Program Manager") && !t.equals("Windows Input Experience");
    }

    private static String title(HWND hwnd) {
        char[] buf = new char[512];
        int n = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
        return new String(buf, 0, Math.max(0, n));
    }

    private static int pid(HWND hwnd) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        return pid.getValue();
    }

    private static String exe(HWND hwnd) {
        int pid = pid(hwnd);
        WinNT.HANDLE p = Kernel32.INSTANCE.OpenProcess(0x1000 /* QUERY_LIMITED_INFORMATION */, false, pid);
        if (p == null) {
            return null;
        }
        try {
            char[] buf = new char[1024];
            IntByReference size = new IntByReference(buf.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(p, 0, buf, size)) {
                return null;
            }
            String path = new String(buf, 0, size.getValue());
            int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            return path.substring(slash + 1);
        } finally {
            Kernel32.INSTANCE.CloseHandle(p);
        }
    }

    /** The window's picture (its visible frame, without the invisible resize border), or null. */
    private static BufferedImage grab(HWND hwnd, BufferedImage reuse) {
        RECT win = new RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, win)) {
            return null;
        }
        RECT vis = new RECT();
        boolean hasVisible = Dwm.INSTANCE != null
                && Dwm.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, vis.getPointer(), vis.size()) == 0;
        if (hasVisible) {
            vis.read();
        }
        int ww = win.right - win.left;
        int wh = win.bottom - win.top;
        if (ww <= 0 || wh <= 0 || ww > 8192 || wh > 8192) {
            return null;
        }
        int cx = hasVisible ? Math.max(0, vis.left - win.left) : 0;
        int cy = hasVisible ? Math.max(0, vis.top - win.top) : 0;
        int cw = hasVisible ? Math.min(ww - cx, vis.right - vis.left) : ww;
        int ch = hasVisible ? Math.min(wh - cy, vis.bottom - vis.top) : wh;
        if (cw <= 0 || ch <= 0) {
            return null;
        }

        HDC screen = User32.INSTANCE.GetDC(null);
        HDC mem = GDI32.INSTANCE.CreateCompatibleDC(screen);
        HBITMAP bmp = GDI32.INSTANCE.CreateCompatibleBitmap(screen, ww, wh);
        WinNT.HANDLE old = GDI32.INSTANCE.SelectObject(mem, bmp);
        try {
            if (!User32.INSTANCE.PrintWindow(hwnd, mem, PW_RENDERFULLCONTENT)) {
                return null;
            }
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = ww;
            bmi.bmiHeader.biHeight = -wh; // top-down rows
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            Memory buf = new Memory((long) ww * wh * 4);
            GDI32.INSTANCE.SelectObject(mem, old); // GetDIBits needs the bitmap out of the DC
            old = null;
            if (GDI32.INSTANCE.GetDIBits(mem, bmp, 0, wh, buf, bmi, WinGDI.DIB_RGB_COLORS) == 0) {
                return null;
            }
            BufferedImage img = reuse != null && reuse.getWidth() == cw && reuse.getHeight() == ch
                    ? reuse : new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < ch; y++) {
                buf.read(((long) (y + cy) * ww + cx) * 4, px, y * cw, cw); // BGRA bytes = 0xAARRGGBB ints
            }
            return img;
        } finally {
            if (old != null) {
                GDI32.INSTANCE.SelectObject(mem, old);
            }
            GDI32.INSTANCE.DeleteObject(bmp);
            GDI32.INSTANCE.DeleteDC(mem);
            User32.INSTANCE.ReleaseDC(null, screen);
        }
    }

    // ------------------------------------------------------------------ Win32 calls not in jna-platform's User32

    private interface Extra extends StdCallLibrary {
        Extra INSTANCE = WINDOWS ? Native.load("user32", Extra.class, W32APIOptions.DEFAULT_OPTIONS) : null;

        boolean IsIconic(HWND hwnd);
    }

    private interface Dwm extends StdCallLibrary {
        Dwm INSTANCE = load();

        int DwmGetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);

        private static Dwm load() {
            try {
                return WINDOWS ? Native.load("dwmapi", Dwm.class, W32APIOptions.DEFAULT_OPTIONS) : null;
            } catch (UnsatisfiedLinkError e) {
                return null;
            }
        }
    }
}
