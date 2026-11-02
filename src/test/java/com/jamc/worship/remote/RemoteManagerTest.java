package com.jamc.worship.remote;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remote Control rules: pairing and approval, default permissions, roles, disconnect, forget, lock, disable, what is saved. */
class RemoteManagerTest {

    private static final String IP = "192.168.1.50";

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TempDir
    Path tempDir;

    private final TestClock clock = new TestClock();
    private final List<RemoteManager.ConnectResult> answers = new ArrayList<>();
    private AppConfig config;
    private Path file;
    private RemoteManager manager;

    @BeforeEach
    void setUp() {
        config = new AppConfig(new AppPaths(tempDir));
        file = tempDir.resolve("config/remote-devices.json");
        manager = new RemoteManager(config, file, clock);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private RemoteManager.ConnectResult ask(String deviceToken, String pairing, String name) {
        return manager.connect(deviceToken, pairing, name, IP, null, answers::add);
    }

    /** Scans the code and is allowed with the default access. */
    private RemoteManager.ConnectResult join(String name) {
        RemoteManager.ConnectResult pending = ask(null, manager.pairingToken(), name);
        assertEquals(RemoteManager.ConnectStatus.PENDING, pending.status());
        manager.approve(pending.requestId(), manager.defaultPermissions(), null);
        return answers.getLast();
    }

    private RemoteManager.ConnectResult comeBack(String deviceToken) {
        return ask(deviceToken, null, null);
    }

    @Test
    void offByDefaultOnPort8080() {
        assertFalse(manager.enabled());
        assertFalse(manager.running());
        assertEquals(8080, manager.port());
        config.set(RemoteManager.KEY_PORT, "80");
        assertEquals(8080, manager.port(), "a port outside the allowed range falls back to the default");
        config.set(RemoteManager.KEY_PORT, "nonsense");
        assertEquals(8080, manager.port());
    }

    @Test
    void aNewDeviceNeedsThePairingToken() {
        assertEquals(RemoteManager.ConnectStatus.PAIRING_REQUIRED, ask(null, null, "A").status());
        assertEquals(RemoteManager.ConnectStatus.PAIRING_REQUIRED, ask(null, "guess", "A").status());
        assertEquals(RemoteManager.ConnectStatus.PAIRING_REQUIRED, ask("made-up", null, "A").status());
        assertTrue(manager.devices().isEmpty());
        assertTrue(manager.pairingRequests().isEmpty());
    }

    @Test
    void aNewDeviceWaitsForTheOperator() {
        RemoteManager.ConnectResult r = ask(null, manager.pairingToken(), "Ryan's iPhone");
        assertEquals(RemoteManager.ConnectStatus.PENDING, r.status());
        assertTrue(manager.devices().isEmpty(), "nothing is trusted until allowed");
        assertEquals("Ryan's iPhone".replace("'", " "), manager.pairingRequests().getFirst().name());
        assertTrue(answers.isEmpty());

        manager.approve(r.requestId(), manager.defaultPermissions(), null);
        assertEquals(RemoteManager.ConnectStatus.OK, answers.getFirst().status());
        assertNotNull(answers.getFirst().newToken());
        assertEquals(1, manager.devices().size());
        assertTrue(manager.pairingRequests().isEmpty());
    }

    @Test
    void denyingSavesNothing() {
        RemoteManager.ConnectResult r = ask(null, manager.pairingToken(), "A");
        manager.deny(r.requestId());
        assertEquals(RemoteManager.ConnectStatus.DENIED, answers.getFirst().status());
        assertTrue(manager.devices().isEmpty());
        assertTrue(manager.pairingRequests().isEmpty());
    }

    @Test
    void configureAccessGivesOnlyWhatWasTicked() {
        RemoteManager.ConnectResult r = ask(null, manager.pairingToken(), "A");
        manager.approve(r.requestId(), RemoteRole.PASTOR.permissions(), "Pastor's iPad");
        RemoteDevice d = answers.getFirst().device();
        assertEquals("Pastor s iPad", d.name());
        assertEquals(Set.of(RemotePermission.PRESENTATION), d.permissions());
    }

    @Test
    void aDeviceThatGivesUpLeavesNoRequest() {
        RemoteManager.ConnectResult r = ask(null, manager.pairingToken(), "A");
        manager.cancelPairing(r.requestId());
        assertTrue(manager.pairingRequests().isEmpty());
    }

    @Test
    void tooManyWaitingDevicesAreTurnedAway() {
        for (int i = 0; i < RemoteManager.MAX_PENDING; i++) {
            assertEquals(RemoteManager.ConnectStatus.PENDING, ask(null, manager.pairingToken(), "D" + i).status());
        }
        assertEquals(RemoteManager.ConnectStatus.BUSY, ask(null, manager.pairingToken(), "more").status());
    }

    @Test
    void allowNewDevicesOffStopsTheQrCodeButNotTrustedDevices() {
        String token = join("A").newToken();
        manager.setAllowNewDevices(false);
        assertEquals(RemoteManager.ConnectStatus.NEW_DEVICES_OFF, ask(null, manager.pairingToken(), "B").status());
        assertEquals(RemoteManager.ConnectStatus.OK, comeBack(token).status());
    }

    @Test
    void aRegeneratedQrCodeStopsTheOldOneButNotTrustedDevices() {
        String old = manager.pairingToken();
        String token = join("A").newToken();
        manager.newPairingToken();
        assertNotEquals(old, manager.pairingToken());
        assertEquals(RemoteManager.ConnectStatus.PAIRING_REQUIRED, ask(null, old, "B").status());
        assertEquals(RemoteManager.ConnectStatus.OK, comeBack(token).status());
    }

    @Test
    void newDeviceGetsLyricsOnlyUnlessTheOperatorChangedTheDefault() {
        RemoteManager.ConnectResult r = join("Phone");
        assertEquals(Set.of(RemotePermission.LYRICS), r.device().permissions());
        assertEquals(RemoteRole.LYRICS_ONLY, RemoteRole.of(r.device().permissions()));
        assertEquals(RemoteManager.Access.ALLOWED, manager.authorize(r.device().id(), RemotePermission.LYRICS));
        assertEquals(RemoteManager.Access.NO_PERMISSION, manager.authorize(r.device().id(), RemotePermission.TAKE));

        manager.setDefaultPermissions(EnumSet.of(RemotePermission.BIBLE));
        assertEquals(Set.of(RemotePermission.BIBLE), join("Next").device().permissions());
    }

    @Test
    void rolesMatchTheirPresetsAndNoRoleIsImplied() {
        assertEquals(RemoteRole.PASTOR, RemoteRole.of(EnumSet.of(RemotePermission.PRESENTATION)));
        assertEquals(RemoteRole.CUSTOM, RemoteRole.of(EnumSet.of(RemotePermission.LYRICS, RemotePermission.TAKE)));
        assertFalse(RemoteRole.WORSHIP_LEADER.permissions().contains(RemotePermission.TAKE));
        assertTrue(RemoteRole.CAMERA_OPERATOR.permissions().contains(RemotePermission.TAKE));
    }

    @Test
    void operatorChecksAndUnchecksPermissionsPerDevice() {
        String a = join("A").device().id();
        String b = join("B").device().id();
        manager.setPermission(a, RemotePermission.BIBLE, true);
        manager.setPermission(a, RemotePermission.LYRICS, false);
        assertEquals(RemoteManager.Access.ALLOWED, manager.authorize(a, RemotePermission.BIBLE));
        assertEquals(RemoteManager.Access.NO_PERMISSION, manager.authorize(a, RemotePermission.LYRICS));
        assertEquals(RemoteManager.Access.NO_PERMISSION, manager.authorize(b, RemotePermission.BIBLE), "other devices are not changed");
        assertEquals(RemoteManager.Access.ALLOWED, manager.authorize(b, RemotePermission.LYRICS));
    }

    @Test
    void disconnectEndsTheSessionButKeepsTheDeviceAndItsPermissions() {
        RemoteManager.ConnectResult r = join("A");
        String id = r.device().id();
        manager.setPermission(id, RemotePermission.SCENES, true);
        manager.disconnect(id);

        assertEquals(RemoteManager.Access.NOT_CONNECTED, manager.authorize(id, RemotePermission.LYRICS));
        assertFalse(manager.devices().getFirst().session());

        RemoteManager.ConnectResult again = manager.connect(r.newToken(), null, null, "192.168.1.99", null, answers::add);
        assertNull(again.newToken(), "a known device keeps its token");
        assertEquals(id, again.device().id());
        assertEquals("A", again.device().name());
        assertEquals("192.168.1.99", again.device().address());
        assertEquals(RemoteManager.Access.ALLOWED, manager.authorize(id, RemotePermission.SCENES));
        assertEquals(1, manager.devices().size());
    }

    @Test
    void theSessionLastsUntilTheLastConnectionCloses() {
        RemoteManager.ConnectResult r = join("A");
        String id = r.device().id();
        comeBack(r.newToken());
        manager.connectionClosed(id);
        assertTrue(manager.devices().getFirst().session(), "the other tab is still open");
        manager.connectionClosed(id);
        assertFalse(manager.devices().getFirst().session());
    }

    @Test
    void forgetRemovesTheDeviceAndItMustPairAgainWithTheDefaults() {
        RemoteManager.ConnectResult r = join("A");
        String id = r.device().id();
        manager.setPermission(id, RemotePermission.TAKE, true);
        manager.forget(id);

        assertTrue(manager.devices().isEmpty());
        assertEquals(RemoteManager.Access.UNKNOWN_DEVICE, manager.authorize(id, RemotePermission.TAKE));
        assertEquals(RemoteManager.ConnectStatus.PAIRING_REQUIRED, comeBack(r.newToken()).status());

        RemoteManager.ConnectResult again = join("A");
        assertNotEquals(r.newToken(), again.newToken(), "the old token is not brought back");
        assertEquals(Set.of(RemotePermission.LYRICS), again.device().permissions());
    }

    @Test
    void lockRefusesCommandsButDevicesStayConnected() {
        RemoteManager.ConnectResult r = join("A");
        String id = r.device().id();
        manager.setLocked(true);
        assertEquals(RemoteManager.Access.LOCKED, manager.authorize(id, RemotePermission.LYRICS));
        assertTrue(manager.devices().getFirst().session());
        assertEquals(RemoteManager.ConnectStatus.OK, comeBack(r.newToken()).status());
        manager.setLocked(false);
        assertEquals(RemoteManager.Access.ALLOWED, manager.authorize(id, RemotePermission.LYRICS));
    }

    @Test
    void disableEndsEveryConnectionAndAcceptsNoneUntilTurnedOff() {
        RemoteManager.ConnectResult r = join("A");
        RemoteManager.ConnectResult waiting = ask(null, manager.pairingToken(), "B");
        assertNotNull(waiting.requestId());
        manager.setDisabled(true);

        assertFalse(manager.devices().getFirst().session());
        assertTrue(manager.pairingRequests().isEmpty());
        assertEquals(RemoteManager.ConnectStatus.DISABLED, answers.getLast().status());
        assertEquals(RemoteManager.ConnectStatus.DISABLED, comeBack(r.newToken()).status());
        assertEquals(RemoteManager.ConnectStatus.DISABLED, ask(null, manager.pairingToken(), "C").status());

        manager.setDisabled(false);
        assertEquals(RemoteManager.ConnectStatus.OK, comeBack(r.newToken()).status());
    }

    @Test
    void lockDisableAndTrustedDevicesSurviveARestartButSessionsDoNot() throws IOException {
        RemoteManager.ConnectResult r = join("A");
        String id = r.device().id();
        manager.setPermission(id, RemotePermission.MEDIA, true);
        manager.setPermission(id, RemotePermission.LYRICS, false);
        manager.setLocked(true);

        RemoteManager reopened = new RemoteManager(config, file, clock);
        assertTrue(reopened.locked());
        RemoteManager.DeviceView view = reopened.devices().getFirst();
        assertEquals("A", view.device().name());
        assertEquals(Set.of(RemotePermission.MEDIA), view.device().permissions(), "a trusted device keeps its permissions");
        assertFalse(view.session());
        assertEquals(RemoteManager.Access.NOT_CONNECTED, reopened.authorize(id, RemotePermission.MEDIA));

        assertFalse(Files.readString(file).contains(r.newToken()), "only a hash of the token is on disk");
    }

    @Test
    void devicesSavedUnderTheOldPermissionNamesKeepThem() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"devices":[{"id":"x","name":"Old","tokenHash":"h","address":"a","permissions":["PRESENTATIONS","PROGRAM"]}]}
                """);
        assertEquals(Set.of(RemotePermission.PRESENTATION, RemotePermission.TAKE),
                new RemoteManager(config, file, clock).devices().getFirst().device().permissions());
    }

    @Test
    void uncheckingEveryPermissionIsKeptAfterARestart() {
        String id = join("A").device().id();
        manager.setPermission(id, RemotePermission.LYRICS, false);
        assertTrue(new RemoteManager(config, file, clock).devices().getFirst().device().permissions().isEmpty());
    }

    @Test
    void aSilentDeviceStopsShowingAsConnected() {
        String id = join("A").device().id();
        assertTrue(manager.devices().getFirst().online());

        clock.now = clock.now.plus(RemoteManager.ONLINE_WINDOW).plus(Duration.ofSeconds(1));
        assertFalse(manager.devices().getFirst().online());

        manager.touch(id, IP);
        assertTrue(manager.devices().getFirst().online());
    }

    @Test
    void theListOfSavedDevicesHasALimit() {
        for (int i = 0; i < RemoteManager.MAX_DEVICES; i++) {
            assertEquals(RemoteManager.ConnectStatus.OK, join("D" + i).status());
        }
        RemoteManager.ConnectResult pending = ask(null, manager.pairingToken(), "one too many");
        manager.approve(pending.requestId(), manager.defaultPermissions(), null);
        assertEquals(RemoteManager.ConnectStatus.FULL, answers.getLast().status());
        assertEquals(RemoteManager.MAX_DEVICES, manager.devices().size());
    }

    @Test
    void namesFromTheNetworkCannotBecomeSwingMarkup() {
        assertEquals("html img src=http://x/y", RemoteDevice.cleanName("<html><img src=http://x/y>"));
        assertEquals("A B", RemoteDevice.cleanName("  A\n\t B "));
        assertNull(RemoteDevice.cleanName("<>  "));
        assertEquals(RemoteDevice.MAX_NAME_LENGTH, RemoteDevice.cleanName("x".repeat(500)).length());
        manager.connect(null, manager.pairingToken(), null, IP, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)", answers::add);
        assertEquals("iPhone", manager.pairingRequests().getFirst().name());
    }

    @Test
    void thePairingLinkCarriesTheToken() {
        manager.refreshNetwork();
        String link = manager.pairingLink();
        if (link != null) { // null on a machine without a network
            assertTrue(link.contains("/pair?"), link);
            assertTrue(link.endsWith("token=" + manager.pairingToken()), link);
        }
    }
}
