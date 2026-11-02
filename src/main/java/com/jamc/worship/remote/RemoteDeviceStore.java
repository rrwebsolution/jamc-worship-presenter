package com.jamc.worship.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The known devices in {@code config/remote-devices.json}. A file of its own because it belongs to this
 * computer's network: library backups (which carry settings.properties) do not include it.
 */
final class RemoteDeviceStore {

    private static final Logger log = LoggerFactory.getLogger(RemoteDeviceStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    RemoteDeviceStore(Path file) {
        this.file = file;
    }

    List<RemoteDevice> load() {
        List<RemoteDevice> devices = new ArrayList<>();
        if (!Files.exists(file)) {
            return devices;
        }
        try {
            for (JsonNode n : JSON.readTree(file.toFile()).path("devices")) {
                String id = n.path("id").asText("");
                String hash = n.path("tokenHash").asText("");
                if (id.isEmpty() || hash.isEmpty()) {
                    continue;
                }
                EnumSet<RemotePermission> permissions = EnumSet.noneOf(RemotePermission.class);
                for (JsonNode p : n.path("permissions")) {
                    RemotePermission parsed = RemotePermission.parse(p.asText());
                    if (parsed != null) {
                        permissions.add(parsed);
                    }
                }
                String name = RemoteDevice.cleanName(n.path("name").asText(null));
                devices.add(new RemoteDevice(id, name == null ? "Remote device" : name, hash, n.path("address").asText(""),
                        permissions, instant(n.path("firstSeen").asText(null)), instant(n.path("lastSeen").asText(null))));
            }
        } catch (IOException e) {
            log.error("Could not read remote devices from {}", file, e);
        }
        return devices;
    }

    void save(List<RemoteDevice> devices) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode list = root.putArray("devices");
        for (RemoteDevice d : devices) {
            ObjectNode n = list.addObject();
            n.put("id", d.id());
            n.put("name", d.name());
            n.put("tokenHash", d.tokenHash());
            n.put("address", d.address());
            ArrayNode permissions = n.putArray("permissions");
            d.permissions().forEach(p -> permissions.add(p.name()));
            n.put("firstSeen", d.firstSeen().toString());
            n.put("lastSeen", d.lastSeen().toString());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not save remote devices to {}", file, e);
        }
    }

    private static Instant instant(String s) {
        try {
            return s == null ? Instant.EPOCH : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
