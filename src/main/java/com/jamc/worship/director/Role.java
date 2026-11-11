package com.jamc.worship.director;

/** What a virtual shot or a stage zone is about. */
public enum Role {
    WIDE("Wide Shot"),
    LEAD_SINGER("Lead Singer"),
    BACKUP_SINGER("Backup Singer"),
    KEYBOARD("Keyboardist"),
    DRUMS("Drummer"),
    OTHER("Other Worship Member");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Roles a stage zone can have (the wide shot has no zone). */
    public static Role[] zoneRoles() {
        return new Role[]{LEAD_SINGER, BACKUP_SINGER, KEYBOARD, DRUMS, OTHER};
    }

    @Override
    public String toString() {
        return label;
    }
}
