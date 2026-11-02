package com.jamc.worship.remote;

import com.fasterxml.jackson.databind.JsonNode;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;

/**
 * The desktop as remotes see it. Called only on the UI thread. Commands take the same paths as the operator's own
 * buttons, so what a remote does appears in the real Preview and Program.
 */
public interface RemoteDesk {

    /** Every slice of state by name (see {@link RemoteServer#SLICES}); a value is null when there is nothing to show. */
    Map<String, Object> snapshot();

    /**
     * Runs a command whose permissions were already checked.
     *
     * @param permissions what this device may do. Without TAKE, nothing it does may reach Program, except what the desktop
     *                    desktop sends live on a tap: lyrics and Bible cards with "click = live", and presentations in LIVE CONTROL
     * @return null when done, or the reason it was not done
     */
    String execute(RemoteCommand command, JsonNode payload, Set<RemotePermission> permissions);

    /** A list from the library. Called on a server thread, never the UI thread, because it may read the database or the internet. */
    Object query(RemoteQuery query, JsonNode params) throws Exception;

    /** Small pictures of Preview and Program, in that order. */
    BufferedImage[] monitorPictures();
}
