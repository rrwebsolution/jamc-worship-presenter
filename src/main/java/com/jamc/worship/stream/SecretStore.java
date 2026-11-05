package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Properties;

/**
 * Stores sensitive values (stream keys) in {@code config/secrets.properties}, encrypted with the
 * Windows user's own key (DPAPI): the file is useless on another computer or Windows account.
 * Values are never logged. On systems without DPAPI the value is only base64-encoded and
 * {@link #isEncrypted()} is false, so the UI can say so.
 */
public final class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);
    private static final String DPAPI = "dpapi:";
    private static final String PLAIN = "b64:";

    private final Path file;
    private final boolean dpapi;

    public SecretStore(Path file) {
        this.file = file;
        this.dpapi = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                && dpapiWorks();
    }

    /** True when values are encrypted with the operating system's credential protection. */
    public boolean isEncrypted() {
        return dpapi;
    }

    public synchronized boolean has(String name) {
        return load().containsKey(name);
    }

    /** The stored value, or null (also when it cannot be decrypted, e.g. after copying to another PC). */
    public synchronized String get(String name) {
        String v = load().getProperty(name);
        if (v == null) {
            return null;
        }
        try {
            if (v.startsWith(DPAPI)) {
                byte[] plain = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(v.substring(DPAPI.length())));
                return new String(plain, StandardCharsets.UTF_8);
            }
            if (v.startsWith(PLAIN)) {
                return new String(Base64.getDecoder().decode(v.substring(PLAIN.length())), StandardCharsets.UTF_8);
            }
        } catch (RuntimeException | Error e) {
            log.warn("Stored secret '{}' could not be read on this computer", name); // never the value
        }
        return null;
    }

    /** Stores (or with null/blank removes) a value. */
    public synchronized void put(String name, String value) {
        Properties p = load();
        if (value == null || value.isBlank()) {
            p.remove(name);
        } else {
            byte[] bytes = value.strip().getBytes(StandardCharsets.UTF_8);
            p.setProperty(name, dpapi
                    ? DPAPI + Base64.getEncoder().encodeToString(com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(bytes))
                    : PLAIN + Base64.getEncoder().encodeToString(bytes));
        }
        save(p);
    }

    /** "••••••••ab12": enough to recognise a key without showing it. */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        String tail = secret.length() > 8 ? secret.substring(secret.length() - 4) : "";
        return "•".repeat(Math.min(16, Math.max(8, secret.length() - tail.length()))) + tail;
    }

    private Properties load() {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                log.warn("Could not read {}", file.getFileName());
            }
        }
        return p;
    }

    private void save(Properties p) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "LiveCat Multimedia secrets (encrypted for this Windows user)");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not save {}", file.getFileName(), e);
        }
    }

    private static boolean dpapiWorks() {
        try {
            byte[] probe = "probe".getBytes(StandardCharsets.UTF_8);
            byte[] back = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
                    com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(probe));
            return java.util.Arrays.equals(probe, back);
        } catch (RuntimeException | Error e) {
            log.warn("Windows credential protection (DPAPI) is not available: stream keys are stored encoded only");
            return false;
        }
    }
}
