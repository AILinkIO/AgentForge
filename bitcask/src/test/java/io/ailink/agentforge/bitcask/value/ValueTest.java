package io.ailink.agentforge.bitcask.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 时间 / 空间值（期望值取自 openCypher TCK 的 temporal / spatial 场景与 clj-bitcask 的实现）。 */
class ValueTest {
    static final Instant NOW = Instant.parse("2024-05-06T07:08:09Z");

    @Test
    void durationIsoRoundTrip() {
        assertEquals("PT0S", Temporals.dur(0, 0, 0, 0).toString());
        assertEquals("P1Y2M10DT2H30M15.5S", Temporals.parseDur("P1Y2M10DT2H30M15.5S").toString());
        assertEquals(new Dur(0, 0, -1, 900_000_000), Temporals.parseDur("PT-0.1S"));
        assertEquals("PT-0.1S", Temporals.parseDur("PT-0.1S").toString());
        assertEquals("PT-23H-59M-59.9S", Temporals.durNeg(Temporals.parseDur("PT23H59M59.9S")).toString());
        assertEquals("P14D", Temporals.parseDur("P2W").toString());
        assertEquals("P17DT12H", Temporals.parseDur("P2.5W").toString());
        assertEquals("P1M15DT5H14M33S", Temporals.parseDur("P1.5M").toString());
        assertEquals(Temporals.parseDur("P2012Y2M2DT14H37M21.545S"), Temporals.parseDur("P2012-02-02T14:37:21.545"));
        assertEquals(Temporals.durNeg(Temporals.parseDur("P1D")), Temporals.parseDur("-P1D"));
        assertThrows(ValueException.class, () -> Temporals.parseDur("P"));
        assertThrows(ValueException.class, () -> Temporals.parseDur("PT"));
        assertThrows(ValueException.class, () -> Temporals.parseDur("nope"));
    }

    @Test
    void durationArithmetic() {
        Dur d = Temporals.durFromMap(Map.of("days", 1, "hours", 12));
        assertEquals("P1DT12H", d.toString());
        assertEquals("PT12H", Temporals.durDiv(Temporals.durFromMap(Map.of("days", 1)), 2).toString());
        assertEquals("PT8H", Temporals.durDiv(Temporals.durFromMap(Map.of("days", 1)), 3).toString());
        assertEquals("P2DT24H", Temporals.durScale(d, 2).toString());
        assertEquals("P1DT30H", Temporals.durScale(d, 1.5).toString());
        assertEquals("P3D", Temporals.durScale(Temporals.durFromMap(Map.of("days", 1)), 3).toString());
        assertEquals("P1DT12H0.001S", Temporals.durPlus(d, Temporals.durFromMap(Map.of("milliseconds", 1))).toString());
        assertThrows(ArithmeticException.class, () -> Temporals.durDiv(d, 0));
        assertEquals(12L, Temporals.field(d, "hours"));
        assertEquals(1L, Temporals.field(d, "days"));
        assertEquals(43200L, Temporals.field(d, "seconds"));
        assertThrows(ValueException.class, () -> Temporals.durFromMap(Map.of("fortnights", 1)));
    }

    @Test
    void parseAndPrintTemporals() {
        assertEquals(LocalDate.of(2015, 7, 21), Temporals.parse("date", "2015-07-21"));
        assertEquals(LocalDate.of(2015, 7, 21), Temporals.parse("date", "20150721"));
        assertEquals(LocalDate.of(2015, 7, 20), Temporals.parse("date", "2015-W30-1"));
        assertEquals(LocalDate.of(2015, 8, 1), Temporals.parse("date", "2015-Q3-32"));
        assertEquals(LocalDate.of(2015, 7, 21), Temporals.parse("date", "2015-202"));
        assertEquals(LocalDate.of(2015, 7, 1), Temporals.parse("date", "2015-07"));
        assertEquals(LocalDate.of(2015, 1, 1), Temporals.parse("date", "2015"));
        assertEquals(LocalTime.of(21, 40, 32, 142_000_000), Temporals.parse("localtime", "214032.142"));
        assertEquals(OffsetTime.of(21, 40, 32, 0, ZoneOffset.ofHours(1)), Temporals.parse("time", "21:40:32+01:00"));
        assertEquals(OffsetTime.of(21, 40, 0, 0, ZoneOffset.UTC), Temporals.parse("time", "2140Z"));
        assertEquals(LocalDateTime.of(2015, 7, 21, 21, 40, 32), Temporals.parse("localdatetime", "2015-07-21T21:40:32"));
        ZonedDateTime z = (ZonedDateTime) Temporals.parse("datetime", "2015-07-21T21:40:32.142+0100");
        assertEquals(ZonedDateTime.of(2015, 7, 21, 21, 40, 32, 142_000_000, ZoneOffset.ofHours(1)), z);
        ZonedDateTime named = (ZonedDateTime) Temporals.parse("datetime", "2015-07-21T21:40:32[Europe/London]");
        assertEquals(ZoneId.of("Europe/London"), named.getZone());
        assertEquals("2015-07-21T21:40:32+01:00[Europe/London]", Temporals.toIso(named));
        assertEquals(named, Temporals.parse("datetime", Temporals.toIso(named)));
        assertEquals("datetime", Temporals.typeName(named));
        assertNull(Temporals.typeName("x"));
        assertThrows(ValueException.class, () -> Temporals.parse("date", "July"));
    }

    @Test
    void constructFromMaps() {
        assertEquals(LocalDate.of(1984, 10, 11), Temporals.construct("date", Map.of("year", 1984, "month", 10, "day", 11), NOW));
        assertEquals(LocalDate.of(1984, 10, 11), Temporals.construct("date", Map.of("year", 1984, "week", 41, "dayOfWeek", 4), NOW));
        assertEquals(LocalDate.of(1984, 10, 11), Temporals.construct("date", Map.of("year", 1984, "ordinalDay", 285), NOW));
        assertEquals(LocalDate.of(1984, 8, 14), Temporals.construct("date", Map.of("year", 1984, "quarter", 3, "dayOfQuarter", 45), NOW));
        assertEquals(LocalDate.of(2024, 5, 6), Temporals.construct("date", null, NOW));
        assertEquals(LocalTime.of(12, 31, 14, 645_876_123),
                Temporals.construct("localtime", Map.of("hour", 12, "minute", 31, "second", 14, "millisecond", 645, "microsecond", 876, "nanosecond", 123), NOW));
        Map<String, Object> m = new HashMap<>();
        m.put("datetime", ZonedDateTime.of(1984, 10, 11, 12, 0, 0, 0, ZoneOffset.ofHours(1)));
        m.put("timezone", "+05:00");
        assertEquals(ZonedDateTime.of(1984, 10, 11, 16, 0, 0, 0, ZoneOffset.ofHours(5)), Temporals.construct("datetime", m, NOW));
        assertEquals(ZonedDateTime.of(2016, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                Temporals.construct("datetime", Map.of("epochSeconds", 1451606400L), NOW));
        assertThrows(ValueException.class, () -> Temporals.construct("date", Map.of("month", 1), NOW));
    }

    @Test
    void truncateFieldsPlusBetween() {
        LocalDateTime t = LocalDateTime.of(2017, 11, 11, 12, 31, 14, 645_876_123);
        assertEquals(LocalDate.of(2017, 1, 1), Temporals.truncate("date", "year", t, NOW));
        assertEquals(LocalDate.of(2010, 1, 1), Temporals.truncate("date", "decade", t, NOW));
        assertEquals(LocalDate.of(2017, 11, 6), Temporals.truncate("date", "week", t, NOW));
        assertEquals(LocalDateTime.of(2017, 11, 11, 12, 31, 14, 645_000_000), Temporals.truncate("localdatetime", "millisecond", t, NOW));
        assertEquals(2017L, Temporals.field(t, "year"));
        assertEquals(45L, Temporals.field(t, "week"));
        assertEquals(645L, Temporals.field(t, "millisecond"));
        assertNull(Temporals.field(t, "timezone"));
        assertEquals(LocalDate.of(1984, 11, 12), Temporals.plus(LocalDate.of(1984, 10, 11), Temporals.parseDur("P1M1DT23H")));
        assertEquals("P1Y1M1DT1H1M1S", Temporals.between(LocalDateTime.of(2000, 1, 1, 0, 0, 0), LocalDateTime.of(2001, 2, 2, 1, 1, 1)).toString());
        assertEquals("P397D", Temporals.between(LocalDate.of(2000, 1, 1), LocalDate.of(2001, 2, 1), Temporals.BetweenMode.DAYS).toString());
        assertEquals("PT1H", Temporals.between(LocalTime.of(10, 0), LocalTime.of(11, 0)).toString());
        assertTrue(Temporals.compare(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2)) < 0);
        assertNull(Temporals.compare(LocalDate.of(2000, 1, 1), LocalTime.NOON));
    }

    @Test
    void points() {
        Point c = Point.construct(Map.of("x", 3, "y", 4));
        assertEquals(new Point(7203, 3, 4, null), c);
        assertEquals(5.0, Point.distance(new Point(7203, 0, 0, null), c));
        Point geo = Point.construct(Map.of("longitude", 12.78, "latitude", 56.7));
        assertEquals(4326, geo.srid());
        assertEquals("wgs-84", geo.field("crs"));
        assertEquals(12.78, geo.field("longitude"));
        assertEquals("point({longitude: 12.78, latitude: 56.7, crs: 'wgs-84'})", geo.toString());
        assertEquals("4326;12.78;56.7", geo.toText());
        assertEquals(geo, Point.parseText(geo.toText()));
        Point p3 = Point.construct(Map.of("x", 1, "y", 2, "z", 3));
        assertEquals(9157, p3.srid());
        assertEquals(p3, Point.parseText(p3.toText()));
        assertNull(Point.distance(c, geo));
        double d = Point.distance(Point.construct(Map.of("longitude", 0, "latitude", 0)), Point.construct(Map.of("longitude", 1, "latitude", 0)));
        assertTrue(Math.abs(d - 111_319) < 100, String.valueOf(d));
        assertTrue(Point.withinBBox(Point.construct(Map.of("longitude", 179.5, "latitude", 0)),
                Point.construct(Map.of("longitude", 179, "latitude", -1)), Point.construct(Map.of("longitude", -179, "latitude", 1))), "跨日界线");
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("x", null);
        withNull.put("y", 1);
        assertNull(Point.construct(withNull));
        assertThrows(ValueException.class, () -> Point.construct(Map.of("x", 1, "y", 2, "crs", "wgs-84-3d")));
    }
}
