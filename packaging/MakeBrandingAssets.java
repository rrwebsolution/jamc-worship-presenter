import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes every branded file from the supplied artwork in src/public/LiveCat logo. Run it from the project
 * folder again when the artwork changes:
 *
 * <pre>java packaging/MakeBrandingAssets.java</pre>
 *
 * <p>White around the shape is removed (artwork already on a transparent background is kept as it is) and
 * the padding cropped. White inside the shape (the cat's face) stays. Writes the app's icon and
 * logo, the installer's app.ico and, when the remote project is next to this one, the remote's icons.
 */
public final class MakeBrandingAssets {

    private static final Path ARTWORK = Path.of("src/public/LiveCat logo");
    private static final Path RESOURCES = Path.of("src/main/resources/branding");
    private static final Path REMOTE_ICONS = Path.of("../jamc-worship-remote/public/icons");
    /** The same sizes as BrandingManager.ICON_SIZES. */
    private static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    private static final Color REMOTE_BACKGROUND = new Color(0x0B1220);

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage icon = crop(removeBackground(ImageIO.read(ARTWORK.resolve("icon.png").toFile())));
        BufferedImage logo = crop(removeBackground(ImageIO.read(ARTWORK.resolve("logo.png").toFile())));

        Files.createDirectories(RESOURCES);
        png(square(icon, 512, 0.02, null), RESOURCES.resolve("livecat-icon.png"));
        png(scale(logo, 1200, logo.getHeight() * 1200 / logo.getWidth()), RESOURCES.resolve("livecat-logo.png"));

        List<byte[]> entries = new ArrayList<>();
        for (int size : ICO_SIZES) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(square(icon, size, 0.02, null), "png", out);
            entries.add(out.toByteArray());
        }
        writeIco(entries, Path.of("packaging/app.ico"));

        if (Files.isDirectory(REMOTE_ICONS)) {
            png(square(icon, 192, 0.04, null), REMOTE_ICONS.resolve("icon-192.png"));
            png(square(icon, 512, 0.04, null), REMOTE_ICONS.resolve("icon-512.png"));
            png(square(icon, 180, 0.10, Color.WHITE), REMOTE_ICONS.resolve("apple-touch-icon.png")); // iOS shows no transparency
            png(square(icon, 512, 0.16, REMOTE_BACKGROUND), REMOTE_ICONS.resolve("icon-maskable-512.png")); // phones cut the edges off
        }
        System.out.println("Branding assets written.");
    }

    /** Makes the white connected to the image's edge transparent and un-blends the soft edge of the shape from it. */
    private static BufferedImage removeBackground(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if ((src.getRGB(0, 0) >>> 24) == 0) {
            return draw(src, w, h); // already on a transparent background: nothing to remove
        }
        boolean[] background = new boolean[w * h];
        int[] stack = new int[w * h];
        int top = 0;
        for (int x = 0; x < w; x++) {
            stack[top++] = x;
            stack[top++] = (h - 1) * w + x;
        }
        for (int y = 1; y < h - 1; y++) {
            stack[top++] = y * w;
            stack[top++] = y * w + w - 1;
        }
        while (top > 0) {
            int p = stack[--top];
            if (background[p] || whiteness(src.getRGB(p % w, p / w)) < 236) {
                continue;
            }
            background[p] = true;
            int x = p % w;
            int y = p / w;
            if (x > 0 && !background[p - 1]) stack[top++] = p - 1;
            if (x < w - 1 && !background[p + 1]) stack[top++] = p + 1;
            if (y > 0 && !background[p - w]) stack[top++] = p - w;
            if (y < h - 1 && !background[p + w]) stack[top++] = p + w;
        }

        boolean[] edge = background.clone();
        for (int pass = 0; pass < 8; pass++) {
            boolean[] grown = edge.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int p = y * w + x;
                    grown[p] = edge[p] || edge[p - 1] || edge[p + 1] || edge[p - w] || edge[p + w];
                }
            }
            edge = grown;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = y * w + x;
                int rgb = src.getRGB(x, y);
                if (background[p]) {
                    continue;
                }
                if (!edge[p]) {
                    out.setRGB(x, y, rgb | 0xFF000000);
                    continue;
                }
                double a = Math.min(1, (1 - whiteness(rgb) / 255.0) / 0.9);
                if (a <= 0) {
                    continue;
                }
                int r = unblend((rgb >> 16) & 255, a);
                int g = unblend((rgb >> 8) & 255, a);
                int b = unblend(rgb & 255, a);
                out.setRGB(x, y, ((int) Math.round(a * 255) << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static int whiteness(int rgb) {
        return Math.min(Math.min((rgb >> 16) & 255, (rgb >> 8) & 255), rgb & 255);
    }

    private static int unblend(int c, double a) {
        return (int) Math.max(0, Math.min(255, Math.round((c - (1 - a) * 255) / a)));
    }

    private static BufferedImage crop(BufferedImage img) {
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = 0;
        int maxY = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 16) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** {@code img} centred in a square with {@code margin} (a share of the size) around it. */
    private static BufferedImage square(BufferedImage img, int size, double margin, Color background) {
        int room = (int) Math.round(size * (1 - 2 * margin));
        double s = Math.min(room / (double) img.getWidth(), room / (double) img.getHeight());
        int w = Math.max(1, (int) Math.round(img.getWidth() * s));
        int h = Math.max(1, (int) Math.round(img.getHeight() * s));
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        if (background != null) {
            g.setColor(background);
            g.fillRect(0, 0, size, size);
        }
        g.drawImage(scale(img, w, h), (size - w) / 2, (size - h) / 2, null);
        g.dispose();
        return out;
    }

    /** Halves step by step before the last bicubic step, so small icons stay sharp. */
    private static BufferedImage scale(BufferedImage img, int w, int h) {
        BufferedImage cur = img;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return draw(cur, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static void png(BufferedImage img, Path file) throws IOException {
        ImageIO.write(img, "png", file.toFile());
    }

    /** An .ico holding each size as a PNG. */
    private static void writeIco(List<byte[]> pngs, Path file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(0);
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) pngs.size()));
            int offset = 6 + 16 * pngs.size();
            for (int i = 0; i < pngs.size(); i++) {
                int size = ICO_SIZES[i];
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(size == 256 ? 0 : size);
                out.writeShort(0);
                out.writeShort(Short.reverseBytes((short) 1));
                out.writeShort(Short.reverseBytes((short) 32));
                out.writeInt(Integer.reverseBytes(pngs.get(i).length));
                out.writeInt(Integer.reverseBytes(offset));
                offset += pngs.get(i).length;
            }
            for (byte[] png : pngs) {
                out.write(png);
            }
        }
        Files.write(file, bytes.toByteArray());
    }
}
