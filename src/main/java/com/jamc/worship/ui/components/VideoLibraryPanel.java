package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.ui.Ui;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.function.Consumer;

/**
 * Library → Videos: one sidebar item with tabs for the videos (all, local, YouTube) and the backgrounds.
 * The tabs only change what is listed; the videos and backgrounds stay filed in their own categories.
 */
public class VideoLibraryPanel extends JPanel {

    private static final String STYLE = "font: semibold; margin: 5,6,5,6; tab.underlineHeight: 3; "
            + "tab.selectedBackground: $Button.toolbar.selectedBackground";
    private static final String VIDEOS_CARD = "videos";
    private static final String BACKGROUNDS_CARD = "backgrounds";

    private final MediaLibraryPanel videos;
    private final MediaLibraryPanel backgrounds;
    private final CardLayout cards = new CardLayout();
    private final JPanel holder = Ui.transparent(cards);
    private final JPanel strip = Ui.transparent(new GridBagLayout());
    private final ButtonGroup group = new ButtonGroup();
    private final JToggleButton backgroundsTab;
    private final JToggleButton allTab;
    private final JToggleButton localTab;
    private MediaLibraryPanel active;
    private Consumer<MediaLibraryPanel> switchListener = p -> { };

    public VideoLibraryPanel(MediaLibraryPanel videos, MediaLibraryPanel backgrounds) {
        super(new BorderLayout());
        this.videos = videos;
        this.backgrounds = backgrounds;
        this.active = videos;
        setOpaque(false);

        allTab = tab("All", "Every video, local files first", () -> showVideos(MediaLibraryPanel.VIDEOS_ALL));
        allTab.setSelected(true);
        localTab = tab("Local", "Video files on this computer (downloaded Pixabay videos too)",
                () -> showVideos(MediaLibraryPanel.VIDEOS_LOCAL));
        tab("YouTube", "Videos that play from YouTube", () -> showVideos(MediaLibraryPanel.VIDEOS_YOUTUBE));
        tab("Pixabay", "Pixabay videos (they stream from Pixabay; + → Pixabay Worship Backgrounds… to find more)",
                () -> showVideos(MediaLibraryPanel.VIDEOS_PIXABAY));
        backgroundsTab = tab("Backgrounds", "Images, videos, colors and gradients for the background", this::showBackgroundsCard);
        strip.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));

        holder.add(videos, VIDEOS_CARD);
        holder.add(backgrounds, BACKGROUNDS_CARD);
        add(strip, BorderLayout.NORTH);
        add(holder, BorderLayout.CENTER);
    }

    /** Told when the tabs change to the other list, which then needs loading and its own selection shown. */
    public void setSwitchListener(Consumer<MediaLibraryPanel> l) {
        switchListener = l;
    }

    /** The list on screen. */
    public MediaLibraryPanel active() {
        return active;
    }

    /** Opens the All videos tab and selects this video there (a video item from the service schedule). */
    public void selectVideo(long id) {
        if (active != videos) {
            allTab.setSelected(true);
            showVideos(MediaLibraryPanel.VIDEOS_ALL);
        }
        videos.select(id);
    }

    /** Opens the Local tab and selects this video there (a Pixabay video just downloaded). */
    public void selectLocal(long id) {
        localTab.setSelected(true);
        showVideos(MediaLibraryPanel.VIDEOS_LOCAL);
        videos.select(id);
    }

    /** Opens the Backgrounds tab and selects this item there. */
    public void selectBackground(long id) {
        showBackgrounds();
        backgrounds.select(id);
    }

    /** Opens the Backgrounds tab (the background controls' Browse, the top bar's background indicator). */
    public void showBackgrounds() {
        backgroundsTab.setSelected(true);
        showBackgroundsCard();
    }

    private JToggleButton tab(String title, String tooltip, Runnable action) {
        JToggleButton b = new JToggleButton(title);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TAB);
        b.putClientProperty(FlatClientProperties.STYLE, STYLE);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        group.add(b);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        strip.add(b, c);
        return b;
    }

    private void showVideos(String filter) {
        videos.showVideos(filter);
        cards.show(holder, VIDEOS_CARD);
        switchTo(videos);
    }

    private void showBackgroundsCard() {
        cards.show(holder, BACKGROUNDS_CARD);
        switchTo(backgrounds);
    }

    private void switchTo(MediaLibraryPanel panel) {
        if (active != panel) {
            active = panel;
            switchListener.accept(panel);
        }
    }
}
