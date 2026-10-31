package com.jamc.worship.presentation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A folder of slides that were exported as pictures (PowerPoint: File → Export → PNG, "All Slides").
 * The images are exactly what PowerPoint drew, so this is the most faithful option on a computer
 * without PowerPoint. Files are ordered by the number in their name (Slide2 before Slide10).
 */
public final class ImageSlideRenderer implements PresentationRenderer {

    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    @Override
    public String name() {
        return "Slide images";
    }

    @Override
    public boolean canOpen(Path source) {
        return Files.isDirectory(source);
    }

    @Override
    public Document open(Path source) throws IOException {
        List<Path> files = images(source);
        if (files.isEmpty()) {
            throw new IOException("The folder has no PNG or JPG slide images.");
        }
        return new Document() {
            @Override
            public int slideCount() {
                return files.size();
            }

            @Override
            public String slideTitle(int index) {
                return null;
            }

            @Override
            public BufferedImage render(int index, int maxWidth, int maxHeight, List<String> warnings) throws IOException {
                BufferedImage img = ImageIO.read(files.get(index).toFile());
                if (img == null) {
                    throw new IOException("Unreadable image " + files.get(index).getFileName());
                }
                return img;
            }

            @Override
            public void close() {
                // nothing held open
            }
        };
    }

    static List<Path> images(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
            }).forEach(out::add);
        }
        out.sort(Comparator.comparingLong(ImageSlideRenderer::number)
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static long number(Path p) {
        Matcher m = NUMBER.matcher(p.getFileName().toString());
        long last = Long.MAX_VALUE;
        while (m.find()) {
            try {
                last = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                last = Long.MAX_VALUE;
            }
        }
        return last;
    }
}
