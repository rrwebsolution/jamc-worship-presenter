package com.jamc.worship.ui;

import com.jamc.worship.service.ValidationException;

import javax.swing.JOptionPane;
import java.awt.Component;

/** Standard confirmation and error dialogs. */
public final class Dialogs {

    private Dialogs() {
    }

    /** Asks before a destructive action. The confirm button carries the action name, never just "OK". */
    public static boolean confirmDestructive(Component parent, String title, String message, String actionLabel) {
        Object[] options = {actionLabel, "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, message, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return choice == 0;
    }

    public static void showError(Component parent, String action, Throwable error) {
        String message = error instanceof ValidationException
                ? error.getMessage()
                : action + "\n\n" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                  + "\n\nDetails were written to the log file.";
        JOptionPane.showMessageDialog(parent, message, BrandingManager.name(), JOptionPane.ERROR_MESSAGE);
    }

    public static void showInfo(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
    }
}
