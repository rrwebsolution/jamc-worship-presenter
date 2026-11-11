package com.jamc.worship.director;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Crop geometry, the person tracker and the shot planner. */
class TrackingAndPlanningTest {

    // ---- crop geometry

    @Test
    void cropNeverLeavesThePicture() {
        CropRect c = new CropRect(0.99, 0.01, 2.0);
        assertEquals(0.75, c.cx(), 1e-9);
        assertEquals(0.25, c.cy(), 1e-9);
        assertEquals(1.0, new CropRect(0.5, 0.5, 0.3).zoom(), "never wider than the picture");
        assertEquals(CropRect.MAX_ZOOM, new CropRect(0.5, 0.5, 99).zoom());
    }

    @Test
    void framingAPersonKeepsThemInsideWithHeadroom() {
        Box person = new Box(0.40, 0.30, 0.10, 0.40);
        CropRect c = CropRect.frame(person, 0.15, 1.5);
        Box b = c.box();
        assertTrue(b.x() <= person.x() && b.right() >= person.right());
        assertTrue(b.y() < person.y(), "headroom above the head");
        assertTrue(b.bottom() >= person.bottom());
        assertEquals(1.5, c.zoom(), 1e-9);
    }

    @Test
    void aBigPersonGetsAWiderFramingNotACutOffHead() {
        Box person = new Box(0.30, 0.05, 0.20, 0.90);
        CropRect c = CropRect.frame(person, 0.1, 2.0);
        assertTrue(c.zoom() < 1.1);
    }

    @Test
    void defaultShotsKeepTheirZoom() {
        List<VirtualShot> d = VirtualShot.defaults();
        assertEquals(1.0, d.get(0).zoom);
        for (VirtualShot s : d.subList(1, d.size())) {
            assertEquals(1.5, s.zoom, s.name);
        }
        assertEquals(List.of("Wide Shot", "Lead Singer", "Keyboardist", "Drummer", "Other Worship Member"),
                d.stream().map(s -> s.name).toList());
    }

    @Test
    void transitionsEndExactlyOnTheNewShot() {
        CropRect a = CropRect.WIDE;
        CropRect b = new CropRect(0.3, 0.4, 2);
        for (ShotTransition t : ShotTransition.values()) {
            assertTrue(t.at(a, b, 1).near(b, 1e-9), t.name());
        }
        assertTrue(ShotTransition.PAN_ZOOM.at(a, b, 0).near(a, 1e-9));
        CropRect mid = ShotTransition.SMOOTH_ZOOM.at(new CropRect(0.2, 0.5, 2), new CropRect(0.8, 0.5, 2), 0.5);
        assertTrue(mid.zoom() < 2, "smooth zoom goes wider in the middle");
    }

    // ---- tracker

    private static PersonDetector.Detection det(double x, double y, double w, double h) {
        return new PersonDetector.Detection(new Box(x, y, w, h), 0.9f);
    }

    @Test
    void trackingIdsStayStableWhilePeopleMove() {
        PersonTracker t = new PersonTracker();
        long now = 0;
        List<PersonTracker.Person> people = List.of();
        for (int i = 0; i < 20; i++) {
            double drift = i * 0.004;
            people = t.update(List.of(det(0.10 + drift, 0.4, 0.1, 0.4), det(0.45 - drift, 0.3, 0.1, 0.5),
                    det(0.80, 0.4, 0.1, 0.4)), null, 1, 1920, 1080, now += 200);
        }
        assertEquals(3, people.size());
        assertEquals(List.of(1, 2, 3), people.stream().map(PersonTracker.Person::id).sorted().toList());
        PersonTracker.Person left = people.stream().filter(p -> p.id() == 1).findFirst().orElseThrow();
        assertEquals(0.10 + 19 * 0.004, left.box().x(), 0.02);
    }

    @Test
    void peopleGetTheRoleOfTheirZone() {
        PersonTracker t = new PersonTracker();
        List<PersonTracker.Person> people = List.of();
        for (int i = 0; i < 3; i++) {
            people = t.update(List.of(det(0.10, 0.4, 0.1, 0.4), det(0.45, 0.3, 0.1, 0.5), det(0.80, 0.4, 0.1, 0.4)),
                    null, 1, 1920, 1080, i * 200L);
        }
        assertEquals(List.of(Role.KEYBOARD, Role.LEAD_SINGER, Role.DRUMS), people.stream().map(PersonTracker.Person::role).toList());
        t.assign(people.get(1).id(), "Pastor Ana", Role.BACKUP_SINGER);
        PersonTracker.Person named = t.snapshot().get(1);
        assertEquals("Pastor Ana", named.name());
        assertEquals(Role.BACKUP_SINGER, named.role());
    }

    @Test
    void aShortLossIsHeldAndAComebackGetsTheSameId() {
        PersonTracker t = new PersonTracker();
        t.setHoldMs(1000);
        long now = 0;
        for (int i = 0; i < 3; i++) {
            t.update(List.of(det(0.45, 0.3, 0.1, 0.5)), null, 1, 1920, 1080, now += 200);
        }
        int id = t.snapshot().get(0).id();
        t.update(List.of(), null, 1, 1920, 1080, now += 500);
        assertEquals(1, t.snapshot().size(), "held during a short loss");
        assertFalse(t.snapshot().get(0).visible());
        t.update(List.of(), null, 1, 1920, 1080, now += 2000);
        assertTrue(t.snapshot().isEmpty(), "dropped after the hold time");
        t.update(List.of(det(0.46, 0.3, 0.1, 0.5)), null, 1, 1920, 1080, now += 200);
        t.update(List.of(det(0.46, 0.3, 0.1, 0.5)), null, 1, 1920, 1080, now += 200);
        assertEquals(id, t.snapshot().get(0).id(), "re-associated, not a new person");
    }

    @Test
    void aSingleFalseDetectionNeverBecomesAPerson() {
        PersonTracker t = new PersonTracker();
        t.update(List.of(det(0.5, 0.5, 0.05, 0.1)), null, 1, 1920, 1080, 0);
        assertTrue(t.snapshot().isEmpty());
        t.update(List.of(), null, 1, 1920, 1080, 1000);
        t.update(List.of(), null, 1, 1920, 1080, 4000);
        assertTrue(t.snapshot().isEmpty());
    }

    @Test
    void lowConfidenceDetectionsAreIgnored() {
        PersonTracker t = new PersonTracker();
        t.setMinConfidence(0.6);
        for (int i = 0; i < 3; i++) {
            t.update(List.of(new PersonDetector.Detection(new Box(0.4, 0.3, 0.1, 0.5), 0.4f)), null, 1, 1920, 1080, i * 200L);
        }
        assertTrue(t.snapshot().isEmpty());
    }

    @Test
    void neverJumpsToSomeoneFarAway() {
        assertTrue(PersonTracker.cost(new Box(0.1, 0.4, 0.1, 0.4), null, new Box(0.8, 0.4, 0.1, 0.4), null) >= 1);
        assertTrue(PersonTracker.cost(new Box(0.1, 0.4, 0.1, 0.4), null, new Box(0.11, 0.4, 0.1, 0.4), null) < 0.5);
    }

    // ---- planner

    @Test
    void plannerRotatesAndComesBackToWide() {
        List<VirtualShot> shots = VirtualShot.defaults();
        ShotPlanner.Rules rules = new ShotPlanner.Rules(4, 600, 3);
        List<String> history = new ArrayList<>();
        VirtualShot current = shots.get(0);
        history.add(current.id);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            current = ShotPlanner.next(shots, current, history, 0, s -> 0.9, rules);
            history.add(current.id);
            order.add(current.name);
        }
        assertEquals("Lead Singer", order.get(0), "highest priority first");
        for (int i = 1; i < order.size(); i++) {
            assertNotEquals(order.get(i - 1), order.get(i));
        }
        assertTrue(order.subList(0, 4).contains("Wide Shot"), "back to wide after at most 3 close shots: " + order);
    }

    @Test
    void plannerGoesWideWhenItHasNotBeenSeenForAWhile() {
        List<VirtualShot> shots = VirtualShot.defaults();
        ShotPlanner.Rules rules = new ShotPlanner.Rules(4, 40, 9);
        VirtualShot next = ShotPlanner.next(shots, shots.get(1), List.of(shots.get(1).id), 45, s -> 0.9, rules);
        assertEquals("Wide Shot", next.name);
    }

    @Test
    void plannerSkipsShotsThatCannotBeShown() {
        List<VirtualShot> shots = VirtualShot.defaults();
        ShotPlanner.Rules rules = new ShotPlanner.Rules(4, 600, 3);
        VirtualShot next = ShotPlanner.next(shots, shots.get(0), List.of(shots.get(0).id), 0,
                s -> s.name.equals("Drummer") ? 0.9 : s.role == Role.WIDE ? 0.9 : 0, rules);
        assertEquals("Drummer", next.name);
    }

    @Test
    void timeToCutRespectsMinimumAndHold() {
        VirtualShot lead = VirtualShot.defaults().get(1); // 12 s
        ShotPlanner.Rules rules = new ShotPlanner.Rules(4, 40, 3);
        assertFalse(ShotPlanner.timeToCut(lead, 3, 0, rules), "never before the minimum, even when lost");
        assertTrue(ShotPlanner.timeToCut(lead, 5, 0, rules), "lost after the minimum");
        assertFalse(ShotPlanner.timeToCut(lead, 8, 0.9, rules));
        assertTrue(ShotPlanner.timeToCut(lead, 12, 0.9, rules));
    }

    @Test
    void lowConfidenceFallsBackToTheZoneNotToNothing() {
        VirtualShot lead = VirtualShot.defaults().get(1);
        assertTrue(ShotPlanner.availability(lead, null, true).score() > 0.1);
        assertEquals(0, ShotPlanner.availability(lead, null, false).score());
    }
}
