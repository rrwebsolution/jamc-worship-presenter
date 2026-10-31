package org.cef.browser;

import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An off-screen Chromium browser that hands every painted frame (BGRA) to its listeners and never
 * shows a window or touches OpenGL.
 *
 * <p>JCEF's own off-screen browser only reports frames after drawing them into a visible JOGL canvas,
 * so it cannot run hidden. This class lives in JCEF's package because {@link CefBrowser_N} is
 * package-private; it is used by {@code com.jamc.worship.youtube.YouTubePlayer}.
 */
public final class HeadlessBrowser extends CefBrowser_N implements CefRenderHandler {

    private volatile Rectangle viewRect;
    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> listeners = new CopyOnWriteArrayList<>();

    public HeadlessBrowser(CefClient client, String url, int width, int height) {
        super(client, url, null, null, null, null);
        viewRect = new Rectangle(0, 0, width, height);
    }

    @Override
    public void createImmediately() {
        if (getNativeRef("CefBrowser") == 0) {
            createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
        }
    }

    /** Changes the page size (the size of the frames delivered). */
    public void resize(int width, int height) {
        viewRect = new Rectangle(0, 0, width, height);
        wasResized(width, height);
    }

    @Override
    public Component getUIComponent() {
        return null;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context,
                                                 CefBrowser_N parent, Point inspectAt) {
        return null;
    }

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return viewRect;
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        info.Set(1.0, 32, 8, false, viewRect, viewRect);
        return true;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        return new Point(viewPoint);
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (popup || listeners.isEmpty()) {
            return;
        }
        CefPaintEvent event = new CefPaintEvent(browser, false, dirtyRects, buffer, width, height);
        for (Consumer<CefPaintEvent> l : listeners) {
            l.accept(event);
        }
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        listeners.clear();
        listeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Frames arrive through the paint listeners"));
    }
}
