package com.jamc.worship.capture;

import com.jamc.worship.media.MediaFrames;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
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
 * DISPLAY CAPTURE sources: a live picture of a whole monitor (Windows only), like OBS. A monitor is only captured
 * while some output asks for its picture; capturing stops a few seconds after the last request. The mouse pointer
 * is drawn in when asked.
 *
 * <p>Capturing the monitor the projector or the program window is on shows that picture inside itself, as in OBS.
 */
public final class DisplayCapture {

    private static final Logger log = LoggerFactory.getLogger(DisplayCapture.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final int FPS = 20;
    private static final long IDLE_MS = 3000;
    private static final int SRCCOPY = 0x00CC0020;
    /** Also layered windows (tooltips, menus, some players). */
    private static final int CAPTUREBLT = 0x40000000;

    /** A monitor: its Windows device name, position and size in pixels. */
    public record Display(String device, int x, int y, int width, int height, boolean primary, int number) {
        public String label() {
            return "Display " + number + "  (" + width + " × " + height + ")" + (primary ? "  ·  main" : "");
        }
    }

    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();
    private static ScheduledExecutorService worker;

    private DisplayCapture() {
    }

    public static boolean supported() {
        return WINDOWS;
    }

    /** The monitors connected now, main one first. */
    public static List<Display> displays() {
        List<Display> out = new ArrayList<>();
        if (!WINDOWS) {
            return out;
        }
        List<WinUser.MONITORINFOEX> infos = new ArrayList<>();
        User32.INSTANCE.EnumDisplayMonitors(null, null, (monitor, dc, rect, data) -> {
            WinUser.MONITORINFOEX info = new WinUser.MONITORINFOEX();
            if (User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue()) {
                infos.add(info);
            }
            return 1;
        }, new WinDef.LPARAM(0));
        infos.sort((a, b) -> Boolean.compare((b.dwFlags & 1) != 0, (a.dwFlags & 1) != 0));
        int n = 1;
        for (WinUser.MONITORINFOEX m : infos) {
            RECT r = m.rcMonitor;
            out.add(new Display(Native.toString(m.szDevice), r.left, r.top, r.right - r.left, r.bottom - r.top,
                    (m.dwFlags & 1) != 0, n++));
        }
        return out;
    }

    /** The latest picture of this monitor, or null while there is none yet. Calling it keeps it captured. */
    public static BufferedImage frame(String device, boolean cursor) {
        if (!WINDOWS) {
            return null;
        }
        String key = (device == null ? "" : device) + (cursor ? "+cursor" : "");
        Target t = TARGETS.computeIfAbsent(key, k -> new Target(device, cursor));
        t.lastAsked = System.currentTimeMillis();
        ensureWorker();
        return t.frame;
    }

    /** Why there is no picture, or null. */
    public static String status(String device, boolean cursor) {
        Target t = TARGETS.get((device == null ? "" : device) + (cursor ? "+cursor" : ""));
        return t == null ? null : t.status;
    }

    private static synchronized void ensureWorker() {
        if (worker == null) {
            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "display-capture");
                th.setDaemon(true);
                return th;
            });
            worker.scheduleWithFixedDelay(DisplayCapture::tick, 0, 1000 / FPS, TimeUnit.MILLISECONDS);
        }
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Target t : TARGETS.values()) {
            if (now - t.lastAsked > IDLE_MS) {
                TARGETS.values().remove(t);
                continue;
            }
            try {
                changed |= t.capture(now);
            } catch (RuntimeException | Error e) {
                t.status = "Capture failed";
                log.debug("Display capture failed for {}", t.device, e);
            }
        }
        if (changed) {
            MediaFrames.frameArrived();
        }
    }

    private static final class Target {
        final String device;
        final boolean cursor;
        volatile long lastAsked;
        volatile BufferedImage frame;
        volatile String status = "Starting the display capture…";
        Display display;
        long lastLookup;
        BufferedImage spare;

        Target(String device, boolean cursor) {
            this.device = device;
            this.cursor = cursor;
        }

        boolean capture(long now) {
            if (display == null || now - lastLookup > 3000) { // monitors come and go, and change resolution
                lastLookup = now;
                List<Display> all = displays();
                display = all.stream().filter(d -> d.device().equals(device)).findFirst()
                        .orElse(device == null && !all.isEmpty() ? all.getFirst() : null);
                if (display == null) {
                    status = "Display not connected: " + device;
                    frame = null;
                    return true;
                }
            }
            BufferedImage img = grab(display, cursor, spare);
            if (img == null) {
                status = "The display could not be captured";
                return false;
            }
            spare = frame;
            frame = img;
            status = null;
            return true;
        }
    }

    /** One picture of the monitor, with the pointer when asked. */
    static BufferedImage grab(Display d, boolean cursor, BufferedImage reuse) {
        int w = d.width();
        int h = d.height();
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
            return null;
        }
        HDC screen = User32.INSTANCE.GetDC(null);
        HDC mem = GDI32.INSTANCE.CreateCompatibleDC(screen);
        HBITMAP bmp = GDI32.INSTANCE.CreateCompatibleBitmap(screen, w, h);
        WinNT.HANDLE old = GDI32.INSTANCE.SelectObject(mem, bmp);
        try {
            if (!GDI32.INSTANCE.BitBlt(mem, 0, 0, w, h, screen, d.x(), d.y(), SRCCOPY | CAPTUREBLT)) {
                return null;
            }
            if (cursor) {
                drawCursor(mem, d);
            }
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = w;
            bmi.bmiHeader.biHeight = -h; // top-down rows
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            Memory buf = new Memory((long) w * h * 4);
            GDI32.INSTANCE.SelectObject(mem, old); // GetDIBits needs the bitmap out of the DC
            old = null;
            if (GDI32.INSTANCE.GetDIBits(mem, bmp, 0, h, buf, bmi, WinGDI.DIB_RGB_COLORS) == 0) {
                return null;
            }
            BufferedImage img = reuse != null && reuse.getWidth() == w && reuse.getHeight() == h
                    ? reuse : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            buf.read(0, px, 0, w * h); // BGRA bytes = 0xAARRGGBB ints
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

    /** The mouse pointer, where it is on this monitor (nothing when it is on another one or hidden). */
    private static void drawCursor(HDC dc, Display d) {
        CursorInfo info = new CursorInfo();
        info.cbSize = info.size();
        if (!Extra.INSTANCE.GetCursorInfo(info) || (info.flags & 1 /* CURSOR_SHOWING */) == 0 || info.hCursor == null) {
            return;
        }
        WinGDI.ICONINFO icon = new WinGDI.ICONINFO();
        int hotX = 0;
        int hotY = 0;
        if (User32.INSTANCE.GetIconInfo(new WinDef.HICON(info.hCursor.getPointer()), icon)) {
            hotX = icon.xHotspot;
            hotY = icon.yHotspot;
            if (icon.hbmMask != null) {
                GDI32.INSTANCE.DeleteObject(icon.hbmMask);
            }
            if (icon.hbmColor != null) {
                GDI32.INSTANCE.DeleteObject(icon.hbmColor);
            }
        }
        int x = info.ptScreenPos.x - d.x() - hotX;
        int y = info.ptScreenPos.y - d.y() - hotY;
        if (x < -64 || y < -64 || x > d.width() || y > d.height()) {
            return;
        }
        Extra.INSTANCE.DrawIconEx(dc, x, y, info.hCursor, 0, 0, 0, null, 0x0003 /* DI_NORMAL */);
    }

    // ------------------------------------------------------------------ Win32 calls not in jna-platform

    /** CURSORINFO. */
    @Structure.FieldOrder({"cbSize", "flags", "hCursor", "ptScreenPos"})
    public static class CursorInfo extends Structure {
        public int cbSize;
        public int flags;
        public WinDef.HCURSOR hCursor;
        public WinDef.POINT ptScreenPos;
    }

    private interface Extra extends StdCallLibrary {
        Extra INSTANCE = WINDOWS ? Native.load("user32", Extra.class, W32APIOptions.DEFAULT_OPTIONS) : null;

        boolean GetCursorInfo(CursorInfo info);

        boolean DrawIconEx(HDC dc, int x, int y, WinDef.HCURSOR icon, int width, int height, int step,
                           WinDef.HBRUSH brush, int flags);
    }
}
