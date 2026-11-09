package com.jamc.worship.monitor;

import com.jamc.worship.camera.CameraStatus;
import com.jamc.worship.monitor.HealthMonitor.Camera;
import com.jamc.worship.monitor.HealthMonitor.Inputs;
import com.jamc.worship.monitor.HealthMonitor.Level;
import com.jamc.worship.stream.StreamEncoder;
import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.stream.StreamManager.Session;
import com.jamc.worship.stream.StreamManager.State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthMonitorTest {

    private static final long GB = 1L << 30;

    private static SystemSampler.Reading cpu(double load) {
        return new SystemSampler.Reading(load, 0.1, 8, 4 * GB, 16 * GB, 100, 1000);
    }

    private static Session idle() {
        return new Session(State.IDLE, null, null, 0, null);
    }

    private static Session live(long frames, long dropped) {
        return new Session(State.ACTIVE, null, new StreamEncoder.Stats(frames, 30, 6000, dropped, 0), 1, null);
    }

    private static Inputs in(SystemSampler.Reading r, Session live, List<Camera> cams) {
        return new Inputs(r, live, idle(), cams, true);
    }

    @Test
    void cpuMustStayHighBeforeItCounts() {
        HealthMonitor m = new HealthMonitor(() -> HealthThresholds.DEFAULTS); // warning 85% for 20 s
        assertEquals(Level.HEALTHY, m.update(in(cpu(0.99), idle(), List.of()), 0).level(), "a spike is normal");
        Level l = null;
        for (long t = 2000; t <= 20_000; t += 2000) {
            l = m.update(in(cpu(0.90), idle(), List.of()), t).level();
        }
        assertEquals(Level.WARNING, l, "high for 20 s: Warning");
        for (long t = 22_000; t <= 44_000; t += 2000) {
            l = m.update(in(cpu(0.97), idle(), List.of()), t).level();
        }
        assertEquals(Level.CRITICAL, l);
        for (long t = 46_000; t <= 70_000; t += 2000) {
            l = m.update(in(cpu(0.30), idle(), List.of()), t).level();
        }
        assertEquals(Level.HEALTHY, l);
        List<HealthMonitor.Alert> alerts = m.alerts();
        assertEquals(Level.HEALTHY, alerts.get(0).level(), "the end of the problem is noted");
        assertTrue(alerts.get(0).text().startsWith("Resolved"));
    }

    @Test
    void stoppingTheStreamOnPurposeIsNotAProblemButAFailureIsCritical() {
        HealthMonitor m = new HealthMonitor(() -> HealthThresholds.DEFAULTS);
        assertEquals(Level.HEALTHY, m.update(in(cpu(0.2), live(100, 0), List.of()), 0).level());
        assertEquals(Level.HEALTHY, m.update(in(cpu(0.2), idle(), List.of()), 1000).level(), "stopped by the operator");
        var r = m.update(in(cpu(0.2), new Session(State.ERROR, "Connection refused", null, 0, null), List.of()), 2000);
        assertEquals(Level.CRITICAL, r.level());
        assertTrue(r.issues().get(0).text().contains("Connection refused"));
    }

    @Test
    void eachProblemIsAnnouncedOnceWhileItComesAndGoes() {
        HealthMonitor m = new HealthMonitor(() -> HealthThresholds.DEFAULTS);
        List<HealthMonitor.Alert> told = new ArrayList<>();
        m.setAlertListener(told::add);
        Camera ok = new Camera("Cam A", CameraStatus.READY, "1920×1080", 30, null);
        Camera gone = new Camera("Cam A", CameraStatus.DISCONNECTED, null, 0, null);
        for (int i = 0; i < 10; i++) { // a loose cable: flapping for 20 s
            m.update(in(cpu(0.2), idle(), List.of(i % 2 == 0 ? gone : ok)), i * 2000L);
        }
        assertEquals(1, told.size(), "told once, not ten times");
        assertEquals(Level.WARNING, told.get(0).level());
        m.update(in(cpu(0.2), idle(), List.of(new Camera("Cam A", CameraStatus.ERROR, null, 0, "busy"))), 21_000);
        assertEquals(2, told.size(), "getting worse is told");
        assertEquals(Level.CRITICAL, told.get(1).level());
    }

    @Test
    void droppedFramesCountOverTheLast30Seconds() {
        HealthMonitor m = new HealthMonitor(() -> HealthThresholds.DEFAULTS); // warning 1%, critical 5%
        m.update(in(cpu(0.2), live(0, 0), List.of()), 0);
        var r = m.update(in(cpu(0.2), live(900, 0), List.of()), 10_000);
        assertEquals(Level.HEALTHY, r.level());
        assertEquals(0.0, r.liveDropTotal(), 1e-9);
        r = m.update(in(cpu(0.2), live(1700, 100), List.of()), 20_000); // 100 of 900 in 10 s
        assertEquals(Level.CRITICAL, r.level());
        // a minute of clean frames later the old burst is out of the window (the total still shows it)
        r = m.update(in(cpu(0.2), live(4000, 100), List.of()), 50_000);
        r = m.update(in(cpu(0.2), live(5000, 100), List.of()), 60_000);
        assertEquals(Level.HEALTHY, r.level());
        assertTrue(r.liveDropTotal() > 1.5);
    }

    @Test
    void camerasAreCounted() {
        HealthMonitor m = new HealthMonitor(() -> HealthThresholds.DEFAULTS);
        var r = m.update(in(cpu(0.2), idle(), List.of(
                new Camera("A", CameraStatus.READY, null, 0, null),
                new Camera("B", CameraStatus.INITIALIZING, null, 0, null),
                new Camera("C", CameraStatus.DISCONNECTED, null, 0, null))), 0);
        assertEquals(2, r.camerasConnected());
        assertEquals(3, r.camerasTotal());
        assertEquals(Level.WARNING, r.level());
    }

    @Test
    void unsupportedReadingsAreNotAProblem() {
        HealthMonitor m = new HealthMonitor(() -> HealthThresholds.DEFAULTS);
        assertEquals(Level.UNKNOWN, m.last().level(), "before the first reading");
        var r = m.update(new Inputs(SystemSampler.Reading.UNKNOWN, null, null, List.of(), true), 0);
        assertEquals(Level.HEALTHY, r.level());
        assertTrue(Double.isNaN(r.cpuAverage()));
        assertEquals(State.IDLE, StreamManager.State.IDLE);
    }
}
