package com.jamc.worship.remote;

/**
 * Lists a remote can ask for, so it can choose from the library by itself, whatever the operator has open. Reading
 * only, and each needs the permission of its kind of content.
 */
public enum RemoteQuery {
    SONGS(RemotePermission.LYRICS),
    BIBLE_VERSIONS(RemotePermission.BIBLE),
    BIBLE_VERSES(RemotePermission.BIBLE),
    PRESENTATIONS(RemotePermission.PRESENTATION),
    PRESENTATION_THUMBNAIL(RemotePermission.PRESENTATION),
    MEDIA_ITEMS(RemotePermission.MEDIA),
    SERVICES(RemotePermission.SERVICE),
    LYRICS_THUMBNAIL(RemotePermission.LYRICS),
    BIBLE_THUMBNAIL(RemotePermission.BIBLE),
    CAMERA_PICTURE(RemotePermission.CAMERAS),
    // what is saved in the service open on the desktop, by kind: a role sees only the kind it may use
    SERVICE_SONGS(RemotePermission.LYRICS),
    SERVICE_BIBLE(RemotePermission.BIBLE),
    SERVICE_PRESENTATIONS(RemotePermission.PRESENTATION),
    SERVICE_MEDIA(RemotePermission.MEDIA);

    private final RemotePermission required;

    RemoteQuery(RemotePermission required) {
        this.required = required;
    }

    public RemotePermission required() {
        return required;
    }

    /** The query with this name in a message, or null. */
    public static RemoteQuery parse(String name) {
        for (RemoteQuery q : values()) {
            if (q.name().equals(name)) {
                return q;
            }
        }
        return null;
    }
}
