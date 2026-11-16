package com.jamc.worship.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/** Who to ask for API keys (Pixabay, YouTube, licensed Bibles): shown wherever the app asks for a key. */
public final class OwnerContact {

    public static final String EMAIL = "rrwebsolutions1998@gmail.com";
    public static final String PHONE = "09358554398";
    public static final String TEXT = "Need an API key? Contact the owner: " + EMAIL + " · " + PHONE;

    private OwnerContact() {
    }

    /** The same line as HTML, for labels that already use HTML. */
    public static String html() {
        return "Need an API key? Contact the owner: <b>" + EMAIL + "</b> · <b>" + PHONE + "</b>";
    }

    /** A small line; clicking it opens a new e-mail to the owner. */
    public static JComponent label() {
        JLabel l = new JLabel("<html>" + html() + "</html>");
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setToolTipText("Click to write an e-mail to " + EMAIL);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) {
                        Desktop.getDesktop().mail(new URI("mailto:" + EMAIL + "?subject=LiveCat%20API%20key"));
                    }
                } catch (Exception ignored) {
                    // no e-mail program: the address is on screen to copy
                }
            }
        });
        return l;
    }
}
