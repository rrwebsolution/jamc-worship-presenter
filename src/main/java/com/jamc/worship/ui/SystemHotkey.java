package com.jamc.worship.ui;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One keyboard shortcut that works while another program has the keyboard (WPS, PowerPoint, a browser): a Windows
 * hot key. While it is registered Windows hands that key to this app only, so the caller registers it only while
 * another program is in front. The action runs on the Swing thread.
 *
 * <p>The hot key belongs to a thread of its own, which waits for Windows' messages; {@link #stop()} ends it.
 */
public final class SystemHotkey {

    private static final Logger log = LoggerFactory.getLogger(SystemHotkey.class);
    private static final java.util.concurrent.atomic.AtomicInteger IDS = new java.util.concurrent.atomic.AtomicInteger(0x4C43);
    /** Each hot key of this app has its own number. */
    private final int ID = IDS.getAndIncrement();

    private final Runnable action;
    private Thread thread;
    /** The key asked for last (null = none). */
    private KeyStroke current;
    private volatile int threadId;
    private volatile String problem;

    public SystemHotkey(Runnable action) {
        this.action = action;
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Why the last key could not be used (another program has it), or null. */
    public String problem() {
        return problem;
    }

    /** Uses this key from now on (null = none); the previous one is given back to Windows. Returns false when refused. */
    public synchronized boolean set(KeyStroke key) {
        if (java.util.Objects.equals(key, current) && (key == null || (thread != null && thread.isAlive()))) {
            return true; // already so
        }
        stop();
        current = key;
        problem = null;
        if (key == null || !supported()) {
            return true;
        }
        int modifiers = WinUser.MOD_NOREPEAT;
        int m = key.getModifiers();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) {
            modifiers |= WinUser.MOD_CONTROL;
        }
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) {
            modifiers |= WinUser.MOD_ALT;
        }
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) {
            modifiers |= WinUser.MOD_SHIFT;
        }
        if ((m & InputEvent.META_DOWN_MASK) != 0) {
            modifiers |= WinUser.MOD_WIN;
        }
        int vk = windowsKey(key.getKeyCode());
        int mods = modifiers;
        CountDownLatch registered = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        thread = new Thread(() -> {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId();
            ok[0] = User32.INSTANCE.RegisterHotKey(null, ID, mods, vk);
            registered.countDown();
            if (!ok[0]) {
                return;
            }
            try {
                WinUser.MSG msg = new WinUser.MSG();
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WinUser.WM_HOTKEY && msg.wParam.intValue() == ID) {
                        SwingUtilities.invokeLater(action);
                    }
                }
            } finally {
                User32.INSTANCE.UnregisterHotKey(null, ID);
            }
        }, "System-Hotkey");
        thread.setDaemon(true);
        thread.start();
        try {
            registered.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!ok[0]) {
            problem = Shortcuts.describe(key) + " is already used by another program. Choose another key.";
            log.warn("Could not register the system hot key {}", Shortcuts.describe(key));
            thread = null;
            current = null; // try again next time
            return false;
        }
        log.info("System hot key {} registered", Shortcuts.describe(key));
        return true;
    }

    /** Gives the key back to Windows. */
    public synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t != null && t.isAlive()) {
            User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The exe name of the program whose window is in front (e.g. "wps.exe"), or null. */
    public static String foregroundExe() {
        if (!supported()) {
            return null;
        }
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) {
                return null;
            }
            com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            com.sun.jna.platform.win32.WinNT.HANDLE p = Kernel32.INSTANCE.OpenProcess(0x1000 /* QUERY_LIMITED_INFORMATION */,
                    false, pid.getValue());
            if (p == null) {
                return null;
            }
            try {
                char[] buf = new char[1024];
                com.sun.jna.ptr.IntByReference size = new com.sun.jna.ptr.IntByReference(buf.length);
                if (!Kernel32.INSTANCE.QueryFullProcessImageName(p, 0, buf, size)) {
                    return null;
                }
                String path = new String(buf, 0, size.getValue());
                return path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
            } finally {
                Kernel32.INSTANCE.CloseHandle(p);
            }
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    /** Java and Windows number most keys the same (letters, digits, arrows, F-keys, Page Up / Down); not these. */
    static int windowsKey(int javaKey) {
        return switch (javaKey) {
            case KeyEvent.VK_ENTER -> 0x0D;
            case KeyEvent.VK_INSERT -> 0x2D;
            case KeyEvent.VK_DELETE -> 0x2E;
            case KeyEvent.VK_BACK_QUOTE -> 0xC0;
            case KeyEvent.VK_COMMA -> 0xBC;
            case KeyEvent.VK_PERIOD -> 0xBE;
            case KeyEvent.VK_SLASH -> 0xBF;
            case KeyEvent.VK_SEMICOLON -> 0xBA;
            case KeyEvent.VK_MINUS -> 0xBD;
            case KeyEvent.VK_EQUALS -> 0xBB;
            default -> javaKey;
        };
    }
}
