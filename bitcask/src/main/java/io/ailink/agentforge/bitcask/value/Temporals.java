package io.ailink.agentforge.bitcask.value;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * openCypher 的时间值：date（{@link LocalDate}）、localtime（{@link LocalTime}）、time（{@link OffsetTime}）、
 * localdatetime（{@link LocalDateTime}）、datetime（{@link ZonedDateTime}）、duration（{@link Dur}）。
 * 解析 / 格式化 ISO-8601、按 map 构造、截断、字段访问、加减与 duration.between（语义同 Neo4j / openCypher TCK）。
 * <p>存储（图层）：时间属性按 ISO 字符串（{@link #toIso}）存进 meta，类型记在保留键 {@code _types}——盘上只有字符串与字符串数组，
 * bitcask / libbitcask 照常能读；读回时按类型名 {@link #parse} 还原。出错一律 {@link ValueException}（英文消息）。
 */
public final class Temporals {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** {@link #between} 的口径：全部 / inMonths / inDays / inSeconds。 */
    public enum BetweenMode { ALL, MONTHS, DAYS, SECONDS }

    private Temporals() {
    }

    private static ValueException fail(String msg) {
        return new ValueException(msg);
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    // ═══ duration ═══

    /** 规范化构造：纳秒落在 [0, 10^9)，多出的并进秒。不经过「总纳秒」，大跨度不溢出（真溢出 ⇒ ArithmeticException）。 */
    public static Dur dur(long months, long days, long seconds, long nanos) {
        return new Dur(months, days, Math.addExact(seconds, Math.floorDiv(nanos, NANOS_PER_SECOND)), Math.floorMod(nanos, NANOS_PER_SECOND));
    }

    /** 朝零截断的整秒（hours / minutes / *OfMinute 等用）。 */
    private static long truncSeconds(Dur d) {
        return d.seconds() < 0 && d.nanos() > 0 ? d.seconds() + 1 : d.seconds();
    }

    /** ISO-8601：P1Y2M10DT2H30M15.5S；全零 ⇒ PT0S。时间部分各分量按符号打印（PT-23H-59M-59.9S）。 */
    public static String durToIso(Dur d) {
        long m = d.months(), dd = d.days();
        boolean neg = d.seconds() < 0;
        long as = neg ? (d.nanos() > 0 ? -(d.seconds() + 1) : -d.seconds()) : d.seconds();
        long an = neg && d.nanos() > 0 ? NANOS_PER_SECOND - d.nanos() : d.nanos();
        String sg = neg ? "-" : "";
        long y = m / 12, mo = m % 12;
        long h = as / 3600, mi = (as % 3600) / 60, sc = as % 60;
        String secs = sc == 0 && an == 0 ? "" : an == 0 ? sg + sc + "S"
                : sg + sc + "." + String.format("%09d", an).replaceAll("0+$", "") + "S";
        String date = (y != 0 ? y + "Y" : "") + (mo != 0 ? mo + "M" : "") + (dd != 0 ? dd + "D" : "");
        String time = (h != 0 ? sg + h + "H" : "") + (mi != 0 ? sg + mi + "M" : "") + secs;
        if (date.isEmpty() && time.isEmpty()) return "PT0S";
        return "P" + date + (time.isEmpty() ? "" : "T" + time);
    }

    private static long exactLong(BigInteger b) {
        return b.longValueExact();
    }

    /**
     * 带小数的分量层层下推：月的小数部分按 30.436875 天、天的小数按 86400 秒、秒的小数截断到纳秒（同 Neo4j）。
     * 全程精确有理数（浮点参数按十进制值换成比值），不受 double 精度影响。
     */
    private static Dur fracCascade(Frac months, Frac days, Frac seconds) {
        Frac m = months.floorFrac(), fm = months.sub(m);
        Frac d1 = days.add(fm.mul(Frac.of(30436875, 1000000)));
        Frac d = d1.floorFrac(), fd = d1.sub(d);
        Frac s1 = seconds.add(fd.mul(Frac.of(86400)));
        Frac s = s1.floorFrac();
        BigInteger n = s1.sub(s).mul(Frac.of(NANOS_PER_SECOND)).floor();
        return dur(exactLong(m.num()), exactLong(d.num()), exactLong(s.num()), exactLong(n));
    }

    /** 单位 ⇒ [月, 日, 秒] 系数。 */
    private static Frac[] unit(String k) {
        return switch (k) {
            case "years" -> new Frac[]{Frac.of(12), Frac.ZERO, Frac.ZERO};
            case "quarters" -> new Frac[]{Frac.of(3), Frac.ZERO, Frac.ZERO};
            case "months" -> new Frac[]{Frac.of(1), Frac.ZERO, Frac.ZERO};
            case "weeks" -> new Frac[]{Frac.ZERO, Frac.of(7), Frac.ZERO};
            case "days" -> new Frac[]{Frac.ZERO, Frac.of(1), Frac.ZERO};
            case "hours" -> new Frac[]{Frac.ZERO, Frac.ZERO, Frac.of(3600)};
            case "minutes" -> new Frac[]{Frac.ZERO, Frac.ZERO, Frac.of(60)};
            case "seconds" -> new Frac[]{Frac.ZERO, Frac.ZERO, Frac.of(1)};
            case "milliseconds" -> new Frac[]{Frac.ZERO, Frac.ZERO, Frac.of(1, 1000)};
            case "microseconds" -> new Frac[]{Frac.ZERO, Frac.ZERO, Frac.of(1, 1000000)};
            case "nanoseconds" -> new Frac[]{Frac.ZERO, Frac.ZERO, Frac.of(1, NANOS_PER_SECOND)};
            default -> null;
        };
    }

    private static long unitNanos(String k) {
        return switch (k) {
            case "hours" -> 3600_000_000_000L;
            case "minutes" -> 60_000_000_000L;
            case "seconds" -> NANOS_PER_SECOND;
            case "milliseconds" -> 1_000_000L;
            case "microseconds" -> 1_000L;
            case "nanoseconds" -> 1L;
            default -> 0L;
        };
    }

    /** duration({years, quarters, months, weeks, days, hours, minutes, seconds, milliseconds, microseconds, nanoseconds})。 */
    public static Dur durFromMap(Map<String, ?> m) {
        Frac mo = Frac.ZERO, d = Frac.ZERO, s = Frac.ZERO;
        boolean allIntegral = true;
        long nanos = 0;
        for (var e : m.entrySet()) {
            String k = e.getKey();
            Frac[] u = unit(k);
            if (u == null) throw fail("unknown duration component `" + k + "`");
            if (!(e.getValue() instanceof Number n)) throw fail("duration component `" + k + "` must be a number");
            Frac v = Frac.of(n);
            mo = mo.add(u[0].mul(v));
            d = d.add(u[1].mul(v));
            s = s.add(u[2].mul(v));
            if (Frac.isIntegral(n)) {
                if (allIntegral) nanos = Math.addExact(nanos, Math.multiplyExact(n.longValue(), unitNanos(k)));
            } else {
                allIntegral = false;
            }
        }
        if (mo.isWhole() && d.isWhole() && allIntegral) return dur(exactLong(mo.num()), exactLong(d.num()), 0, nanos);
        return fracCascade(mo, d, s);
    }

    private static final Pattern DUR = Pattern.compile("(?i)P(?:(-?\\d+(?:\\.\\d+)?)Y)?(?:(-?\\d+(?:\\.\\d+)?)M)?(?:(-?\\d+(?:\\.\\d+)?)W)?"
            + "(?:(-?\\d+(?:\\.\\d+)?)D)?(?:T(?:(-?\\d+(?:\\.\\d+)?)H)?(?:(-?\\d+(?:\\.\\d+)?)M)?(?:(-?\\d+(?:\\.\\d+)?)S)?)?");
    private static final Pattern DUR_ALT = Pattern.compile("(?i)P(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Pattern FRAC_SEC = Pattern.compile("(-?)(\\d+)\\.(\\d+)");

    private static Number num(String x) {
        if (x == null) return null;
        return x.contains(".") ? (Number) Double.parseDouble(x) : (Number) Long.parseLong(x);
    }

    private static Dur negateIf(boolean neg, Dur d) {
        return neg ? durNeg(d) : d;
    }

    /** ISO-8601 duration：P[nY][nM][nW][nD][T[nH][nM][n[.f]S]]，可带负号；也认 P2012-02-02T14:37:21.545 这种日期形式。 */
    public static Dur parseDur(String s0) {
        boolean neg = s0.startsWith("-");
        String s = neg || s0.startsWith("+") ? s0.substring(1) : s0;
        Matcher m = DUR.matcher(s);
        if (m.matches() && !s.equals("P") && !s.toUpperCase().endsWith("T")) {
            String sec = m.group(7);
            Number secI;
            Long secN = null;
            Matcher fm = sec == null ? null : FRAC_SEC.matcher(sec);
            if (fm != null && fm.matches()) {
                long ns = Long.parseLong((fm.group(3) + "000000000").substring(0, 9));
                long si = Long.parseLong(fm.group(2));
                boolean minus = "-".equals(fm.group(1));
                secI = minus ? -si : si;
                secN = minus ? -ns : ns;
            } else {
                secI = num(sec);
            }
            Map<String, Object> parts = new LinkedHashMap<>();
            putIf(parts, "years", num(m.group(1)));
            putIf(parts, "months", num(m.group(2)));
            putIf(parts, "weeks", num(m.group(3)));
            putIf(parts, "days", num(m.group(4)));
            putIf(parts, "hours", num(m.group(5)));
            putIf(parts, "minutes", num(m.group(6)));
            putIf(parts, "seconds", secI);
            putIf(parts, "nanoseconds", secN);
            return negateIf(neg, durFromMap(parts));
        }
        Matcher alt = DUR_ALT.matcher(s);
        if (alt.matches()) {
            String sec = alt.group(6);
            Map<String, Object> parts = new LinkedHashMap<>();
            parts.put("years", Long.parseLong(alt.group(1)));
            parts.put("months", Long.parseLong(alt.group(2)));
            parts.put("days", Long.parseLong(alt.group(3)));
            parts.put("hours", Long.parseLong(alt.group(4)));
            parts.put("minutes", Long.parseLong(alt.group(5)));
            parts.put("seconds", sec.contains(".") ? new java.math.BigDecimal(sec) : (Object) Long.parseLong(sec));
            return negateIf(neg, durFromMap(parts));
        }
        throw fail("invalid duration string " + quote(s));
    }

    private static void putIf(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    public static Dur durPlus(Dur a, Dur b) {
        return dur(Math.addExact(a.months(), b.months()), Math.addExact(a.days(), b.days()), Math.addExact(a.seconds(), b.seconds()),
                a.nanos() + b.nanos());
    }

    public static Dur durNeg(Dur a) {
        return dur(Math.negateExact(a.months()), Math.negateExact(a.days()), Math.negateExact(a.seconds()), -a.nanos());
    }

    private static Frac secondsOf(Dur a) {
        return Frac.of(a.seconds()).add(Frac.of(a.nanos(), NANOS_PER_SECOND));
    }

    /** duration ÷ 数：各分量直接除（不乘倒数，免得 1/3 一类的舍入误差）。除以 0 ⇒ ArithmeticException。 */
    public static Dur durDiv(Dur a, Number f) {
        Frac x = Frac.of(f);
        return fracCascade(Frac.of(a.months()).div(x), Frac.of(a.days()).div(x), secondsOf(a).div(x));
    }

    /** duration × 数。 */
    public static Dur durScale(Dur a, Number f) {
        if (Frac.isIntegral(f)) {
            long k = f instanceof BigInteger b ? b.longValueExact() : f.longValue();
            return dur(Math.multiplyExact(a.months(), k), Math.multiplyExact(a.days(), k), Math.multiplyExact(a.seconds(), k),
                    Math.multiplyExact(a.nanos(), k));
        }
        Frac x = Frac.of(f);
        return fracCascade(Frac.of(a.months()).mul(x), Frac.of(a.days()).mul(x), secondsOf(a).mul(x));
    }

    // ═══ 时间点 ═══

    /** 时间点或 duration。 */
    public static boolean isTemporal(Object x) {
        return x instanceof LocalDate || x instanceof LocalTime || x instanceof OffsetTime || x instanceof LocalDateTime
                || x instanceof ZonedDateTime || x instanceof Dur;
    }

    /** 类型名（date / localtime / time / localdatetime / datetime / duration）；不是时间值 ⇒ null。 */
    public static String typeName(Object x) {
        return switch (x) {
            case LocalDate d -> "date";
            case LocalTime t -> "localtime";
            case OffsetTime t -> "time";
            case LocalDateTime t -> "localdatetime";
            case ZonedDateTime t -> "datetime";
            case Dur d -> "duration";
            case null, default -> null;
        };
    }

    /**
     * ISO-8601 文本（toString / 存储用）：java.time 的 toString（秒为 0 时省略秒，同 openCypher TCK / Neo4j）；
     * datetime 带具名时区时附 [Region/City]。
     */
    public static String toIso(Object x) {
        return String.valueOf(x);
    }

    /** 去掉时区：datetime ⇒ localdatetime、time ⇒ localtime；其余原样。 */
    public static Object localPart(Object x) {
        if (x instanceof ZonedDateTime z) return z.toLocalDateTime();
        if (x instanceof OffsetTime t) return t.toLocalTime();
        return x;
    }

    private static final Pattern HHMM = Pattern.compile("[+-]\\d{4}");

    static ZoneId zone(Object z) {
        if (z instanceof ZoneId id) return id;
        if (z instanceof String s) {
            try {
                return ZoneId.of(HHMM.matcher(s).matches() ? s.substring(0, 3) + ":" + s.substring(3) : s);
            } catch (DateTimeException e) {
                throw fail("invalid timezone " + quote(s));
            }
        }
        throw fail("invalid timezone " + z);
    }

    private static final Pattern D_YMD = Pattern.compile("([+-]?\\d{4,9})-(\\d{2})-(\\d{2})");
    private static final Pattern D_YMD_BASIC = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})");
    private static final Pattern D_WEEK = Pattern.compile("(\\d{4})-?W(\\d{2})(?:-?(\\d))?");
    private static final Pattern D_QUARTER = Pattern.compile("(\\d{4})-?Q(\\d)(?:-?(\\d{2}))?");
    private static final Pattern D_ORDINAL = Pattern.compile("(\\d{4})-?(\\d{3})");
    private static final Pattern D_YM_BASIC = Pattern.compile("(\\d{4})(\\d{2})");
    private static final Pattern D_YM = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final Pattern D_Y = Pattern.compile("(\\d{4})");
    private static final Pattern T_TIME = Pattern.compile("(\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d{1,9}))?)?)?");
    private static final Pattern ZONE_TAIL = Pattern.compile("(.*?)(Z|z|[+-]\\d{2}(?::?\\d{2})?)?(?:\\[([^\\]]+)])?");

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    private static LocalDate parseDatePart(String s) {
        Matcher m;
        if ((m = D_YMD.matcher(s)).matches()) return LocalDate.of(i(m.group(1)), i(m.group(2)), i(m.group(3)));
        if ((m = D_YMD_BASIC.matcher(s)).matches()) return LocalDate.of(i(m.group(1)), i(m.group(2)), i(m.group(3)));
        if ((m = D_WEEK.matcher(s)).matches()) {
            int y = i(m.group(1));
            return LocalDate.of(y, 6, 1).with(IsoFields.WEEK_BASED_YEAR, y).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, i(m.group(2)))
                    .with(WeekFields.ISO.dayOfWeek(), m.group(3) != null ? i(m.group(3)) : 1);
        }
        if ((m = D_QUARTER.matcher(s)).matches()) {
            return LocalDate.of(i(m.group(1)), 1, 1).with(IsoFields.QUARTER_OF_YEAR, i(m.group(2)))
                    .with(IsoFields.DAY_OF_QUARTER, m.group(3) != null ? i(m.group(3)) : 1);
        }
        if ((m = D_ORDINAL.matcher(s)).matches()) return LocalDate.ofYearDay(i(m.group(1)), i(m.group(2)));
        if ((m = D_YM_BASIC.matcher(s)).matches()) return LocalDate.of(i(m.group(1)), i(m.group(2)), 1);
        if ((m = D_YM.matcher(s)).matches()) return LocalDate.of(i(m.group(1)), i(m.group(2)), 1);
        if ((m = D_Y.matcher(s)).matches()) return LocalDate.of(i(m.group(1)), 1, 1);
        throw fail("invalid date string " + quote(s));
    }

    private static LocalTime parseTimePart(String s) {
        Matcher m = T_TIME.matcher(s);
        if (!m.matches()) throw fail("invalid time string " + quote(s));
        return LocalTime.of(i(m.group(1)), m.group(2) != null ? i(m.group(2)) : 0, m.group(3) != null ? i(m.group(3)) : 0,
                m.group(4) != null ? i((m.group(4) + "000000000").substring(0, 9)) : 0);
    }

    private record Split(String body, ZoneId zone) {
    }

    /** 文本尾部的时区：Z / ±HH[:MM] / [Region/City] ⇒ 主体 + ZoneId（没有 ⇒ null）。 */
    private static Split splitZone(String s) {
        Matcher m = ZONE_TAIL.matcher(s);
        if (!m.matches()) return new Split(s, null);
        String body = m.group(1), off = m.group(2), region = m.group(3);
        ZoneId z;
        if (region != null) z = zone(region);
        else if (off == null) z = null;
        else if (off.equals("Z") || off.equals("z")) z = ZoneOffset.UTC;
        else z = ZoneOffset.of(off.length() == 3 || off.contains(":") ? off : off.substring(0, 3) + ":" + off.substring(3));
        return new Split(body, z);
    }

    /** 按类型名（{@link #typeName}）解析 ISO 文本。 */
    public static Object parse(String type, String s) {
        return switch (type) {
            case "date" -> parseDatePart(s);
            case "localtime" -> parseTimePart(s);
            case "time" -> {
                Split sp = splitZone(s);
                yield OffsetTime.of(parseTimePart(sp.body), sp.zone instanceof ZoneOffset o ? o : ZoneOffset.UTC);
            }
            case "localdatetime" -> {
                String[] dt = s.split("[Tt]", 2);
                yield LocalDateTime.of(parseDatePart(dt[0]), dt.length > 1 ? parseTimePart(dt[1]) : LocalTime.MIDNIGHT);
            }
            case "datetime" -> {
                Split sp = splitZone(s);
                String[] dt = sp.body.split("[Tt]", 2);
                yield ZonedDateTime.of(LocalDateTime.of(parseDatePart(dt[0]), dt.length > 1 ? parseTimePart(dt[1]) : LocalTime.MIDNIGHT),
                        sp.zone != null ? sp.zone : ZoneOffset.UTC);
            }
            case "duration" -> parseDur(s);
            default -> throw fail("unknown temporal type " + quote(type));
        };
    }

    // ═══ 按 map 构造 ═══

    private static Integer intField(Map<String, ?> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!Frac.isIntegral(v)) throw fail("`" + k + "` must be an integer");
        return Math.toIntExact(((Number) v).longValue());
    }

    private static boolean hasAny(Map<String, ?> m, String... ks) {
        for (String k : ks) if (m.containsKey(k)) return true;
        return false;
    }

    private static LocalDate baseDate(Object base) {
        if (base instanceof LocalDate d) return d;
        if (base instanceof LocalDateTime t) return t.toLocalDate();
        if (base instanceof ZonedDateTime z) return z.toLocalDate();
        return null;
    }

    private static LocalTime baseTime(Object base) {
        if (base instanceof LocalTime t) return t;
        if (base instanceof OffsetTime t) return t.toLocalTime();
        if (base instanceof LocalDateTime t) return t.toLocalTime();
        if (base instanceof ZonedDateTime z) return z.toLocalTime();
        return null;
    }

    private static <T> T or(T a, T b) {
        return a != null ? a : b;
    }

    private static LocalDate dateFromMap(Map<String, ?> m, Object base) {
        Integer y = intField(m, "year");
        LocalDate b = baseDate(base);
        if (y == null && b != null && !hasAny(m, "month", "day", "week", "dayOfWeek", "ordinalDay", "quarter", "dayOfQuarter")) return b;
        if (m.containsKey("week")) {
            int year = y != null ? y : b != null ? b.getYear() : 2000;
            long wby = y != null ? y : b.get(IsoFields.WEEK_BASED_YEAR);
            Integer dow = intField(m, "dayOfWeek");
            return LocalDate.of(year, 6, 1).with(IsoFields.WEEK_BASED_YEAR, wby).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, intField(m, "week"))
                    .with(WeekFields.ISO.dayOfWeek(), dow != null ? dow : b != null ? b.getDayOfWeek().getValue() : 1);
        }
        if (m.containsKey("ordinalDay")) return LocalDate.ofYearDay(y != null ? y : b.getYear(), intField(m, "ordinalDay"));
        if (m.containsKey("quarter")) {
            Integer doq = intField(m, "dayOfQuarter");
            return LocalDate.of(y != null ? y : b.getYear(), 1, 1).with(IsoFields.QUARTER_OF_YEAR, intField(m, "quarter"))
                    .with(IsoFields.DAY_OF_QUARTER, doq != null ? doq : b != null ? b.get(IsoFields.DAY_OF_QUARTER) : 1);
        }
        // 基准值 + 只给 dayOfWeek / dayOfQuarter：在同一周 / 同一季度里挪
        if (b != null && m.containsKey("dayOfWeek") && !hasAny(m, "month", "day")) {
            LocalDate r = y != null ? b.withYear(y) : b;
            return r.with(WeekFields.ISO.dayOfWeek(), intField(m, "dayOfWeek"));
        }
        if (b != null && m.containsKey("dayOfQuarter") && !hasAny(m, "month", "day")) {
            LocalDate r = y != null ? b.withYear(y) : b;
            return r.with(IsoFields.DAY_OF_QUARTER, intField(m, "dayOfQuarter"));
        }
        if (y == null && b == null) throw fail("date needs a year");
        Integer month = intField(m, "month"), day = intField(m, "day");
        return LocalDate.of(y != null ? y : b.getYear(), month != null ? month : b != null ? b.getMonthValue() : 1,
                day != null ? day : b != null ? b.getDayOfMonth() : 1);
    }

    private static LocalTime timeFromMap(Map<String, ?> m, Object base) {
        LocalTime b = baseTime(base);
        Integer ms = intField(m, "millisecond"), us = intField(m, "microsecond"), nn = intField(m, "nanosecond");
        long bn = b != null ? b.getNano() : 0;
        boolean anySub = ms != null || us != null || nn != null;
        long ns;
        if (b != null && (us == null || (0 <= us && us <= 999)) && (nn == null || (0 <= nn && nn <= 999))) {
            // 毫秒 / 微秒（千分位）/ 纳秒（千分位）分别取给定值或基准值
            ns = 1_000_000L * (ms != null ? ms : bn / 1_000_000) + 1000L * (us != null ? us : (bn / 1000) % 1000) + (nn != null ? nn : bn % 1000);
        } else {
            ns = 1_000_000L * (ms != null ? ms : 0) + 1000L * (us != null ? us : 0) + (nn != null ? nn : 0);
        }
        Integer h = intField(m, "hour"), mi = intField(m, "minute"), s = intField(m, "second");
        return LocalTime.of(h != null ? h : b != null ? b.getHour() : 0, mi != null ? mi : b != null ? b.getMinute() : 0,
                s != null ? s : b != null ? b.getSecond() : 0, Math.toIntExact(anySub ? ns : bn));
    }

    private static ZonedDateTime nowIn(Instant now, Object z) {
        return ZonedDateTime.ofInstant(now, z != null ? zone(z) : ZoneOffset.UTC);
    }

    private static ZoneOffset offsetOf(ZoneId z, Instant now) {
        return z instanceof ZoneOffset o ? o : z.getRules().getOffset(now);
    }

    /**
     * date / localtime / time / localdatetime / datetime 的构造：{@code arg} null ⇒ 当前时刻（{@code now} 是语句时钟）；
     * 字符串 ⇒ 解析；map ⇒ 按字段构造（可带 date / time / datetime 基准值、timezone、epochSeconds / epochMillis）；时间值 ⇒ 转换。
     */
    @SuppressWarnings("unchecked")
    public static Object construct(String type, Object arg, Instant now) {
        if (arg == null) {
            ZonedDateTime z = nowIn(now, null);
            return fromZoned(type, z);
        }
        if (arg instanceof String s) return parse(type, s);
        if (arg instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            Object tz = m.get("timezone");
            if (m.keySet().equals(Set.of("timezone"))) return fromZoned(type, nowIn(now, tz));
            if (type.equals("datetime") && (m.containsKey("epochSeconds") || m.containsKey("epochMillis"))) {
                Integer nano = intField(m, "nanosecond");
                Instant inst = m.containsKey("epochMillis") ? Instant.ofEpochMilli(((Number) m.get("epochMillis")).longValue())
                        : Instant.ofEpochSecond(((Number) m.get("epochSeconds")).longValue(), nano != null ? nano : 0);
                return ZonedDateTime.ofInstant(inst, tz != null ? zone(tz) : ZoneOffset.UTC);
            }
            Object tbase = or(m.get("time"), m.get("datetime"));
            Object dbase = or(m.get("date"), m.get("datetime"));
            Object x = or(m.get("datetime"), m.get("time"));
            ZoneId src = x instanceof ZonedDateTime z ? z.getZone() : x instanceof OffsetTime t ? t.getOffset() : null;
            ZoneId tzid = tz != null ? zone(tz) : null;
            return switch (type) {
                case "date" -> dateFromMap(m, dbase);
                case "localtime" -> timeFromMap(m, tbase);
                // 基准值带时区、又给了 timezone：先在原时区里套字段，再换算到新时区（同一时刻）；否则直接用给的 / 原来的时区
                case "time" -> {
                    OffsetTime r = OffsetTime.of(timeFromMap(m, tbase), offsetOf(or(src, or(tzid, ZoneOffset.UTC)), now));
                    yield tzid != null && src != null ? r.withOffsetSameInstant(offsetOf(tzid, now)) : r;
                }
                case "localdatetime" -> LocalDateTime.of(dateFromMap(m, dbase), timeFromMap(m, tbase));
                case "datetime" -> {
                    ZonedDateTime r = ZonedDateTime.of(LocalDateTime.of(dateFromMap(m, dbase), timeFromMap(m, tbase)), or(src, or(tzid, ZoneOffset.UTC)));
                    yield tzid != null && src != null ? r.withZoneSameInstant(tzid) : r;
                }
                default -> throw fail("unknown temporal type " + quote(type));
            };
        }
        return switch (type) {
            case "date" -> switch (arg) {
                case LocalDate d -> d;
                case LocalDateTime t -> t.toLocalDate();
                case ZonedDateTime z -> z.toLocalDate();
                default -> throw fail("cannot convert to date");
            };
            case "localtime" -> {
                LocalTime t = baseTime(arg);
                if (t == null) throw fail("cannot convert to localtime");
                yield t;
            }
            case "time" -> switch (arg) {
                case OffsetTime t -> t;
                case LocalTime t -> OffsetTime.of(t, ZoneOffset.UTC);
                case ZonedDateTime z -> z.toOffsetDateTime().toOffsetTime();
                case LocalDateTime t -> OffsetTime.of(t.toLocalTime(), ZoneOffset.UTC);
                default -> throw fail("cannot convert to time");
            };
            case "localdatetime" -> switch (arg) {
                case LocalDateTime t -> t;
                case ZonedDateTime z -> z.toLocalDateTime();
                case LocalDate d -> d.atStartOfDay();
                default -> throw fail("cannot convert to localdatetime");
            };
            case "datetime" -> switch (arg) {
                case ZonedDateTime z -> z;
                case LocalDateTime t -> ZonedDateTime.of(t, ZoneOffset.UTC);
                case LocalDate d -> d.atStartOfDay(ZoneOffset.UTC);
                default -> throw fail("cannot convert to datetime");
            };
            default -> throw fail("unknown temporal type " + quote(type));
        };
    }

    private static Object fromZoned(String type, ZonedDateTime z) {
        return switch (type) {
            case "date" -> z.toLocalDate();
            case "localtime" -> z.toLocalTime();
            case "time" -> z.toOffsetDateTime().toOffsetTime();
            case "localdatetime" -> z.toLocalDateTime();
            case "datetime" -> z;
            default -> throw fail("unknown temporal type " + quote(type));
        };
    }

    // ═══ 截断 ═══

    /**
     * date.truncate('month', d) 一类：单位 millennium / century / decade / year / weekYear / quarter / month / week / day /
     * hour / minute / second / millisecond / microsecond。
     */
    public static Object truncate(String type, String unit, Object x, Instant now) {
        LocalDateTime ldt = switch (x) {
            case LocalDate d -> d.atStartOfDay();
            case LocalDateTime t -> t;
            case ZonedDateTime z -> z.toLocalDateTime();
            case LocalTime t -> LocalDateTime.of(LocalDate.of(2000, 1, 1), t);
            case OffsetTime t -> LocalDateTime.of(LocalDate.of(2000, 1, 1), t.toLocalTime());
            case null, default -> throw fail("truncate needs a temporal value");
        };
        LocalDate d = ldt.toLocalDate();
        int y = d.getYear();
        LocalDateTime r = switch (unit) {
            case "millennium" -> LocalDate.of((int) (1000 * Math.floorDiv(y, 1000L)), 1, 1).atStartOfDay();
            case "century" -> LocalDate.of((int) (100 * Math.floorDiv(y, 100L)), 1, 1).atStartOfDay();
            case "decade" -> LocalDate.of((int) (10 * Math.floorDiv(y, 10L)), 1, 1).atStartOfDay();
            case "year" -> LocalDate.of(y, 1, 1).atStartOfDay();
            case "weekYear" -> d.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case "quarter" -> d.with(IsoFields.DAY_OF_QUARTER, 1).atStartOfDay();
            case "month" -> d.withDayOfMonth(1).atStartOfDay();
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case "day" -> d.atStartOfDay();
            case "hour" -> ldt.truncatedTo(ChronoUnit.HOURS);
            case "minute" -> ldt.truncatedTo(ChronoUnit.MINUTES);
            case "second" -> ldt.truncatedTo(ChronoUnit.SECONDS);
            case "millisecond" -> ldt.truncatedTo(ChronoUnit.MILLIS);
            case "microsecond" -> ldt.truncatedTo(ChronoUnit.MICROS);
            default -> throw fail("unknown truncation unit " + quote(unit));
        };
        return switch (type) {
            case "date" -> r.toLocalDate();
            case "localdatetime" -> r;
            case "datetime" -> ZonedDateTime.of(r, x instanceof ZonedDateTime z ? z.getZone() : ZoneOffset.UTC);
            case "localtime" -> r.toLocalTime();
            case "time" -> OffsetTime.of(r.toLocalTime(), x instanceof OffsetTime t ? t.getOffset() : x instanceof ZonedDateTime z ? z.getOffset() : ZoneOffset.UTC);
            default -> throw fail("unknown temporal type " + quote(type));
        };
    }

    // ═══ 字段 ═══

    /** 时间值的字段（d.year、t.hour、dt.timezone、dur.months …）；没有这个字段 ⇒ null。整数字段回 Long。 */
    public static Object field(Object x, String k) {
        if (x instanceof Dur d) {
            long m = d.months(), dd = d.days(), s = d.seconds(), n = d.nanos(), ts = truncSeconds(d);
            return switch (k) {
                case "years" -> m / 12;
                case "quarters" -> m / 3;
                case "months" -> m;
                case "weeks" -> dd / 7;
                case "days" -> dd;
                case "hours" -> ts / 3600;
                case "minutes" -> ts / 60;
                case "seconds" -> s;
                case "milliseconds" -> Math.addExact(Math.multiplyExact(s, 1000L), n / 1_000_000);
                case "microseconds" -> Math.addExact(Math.multiplyExact(s, 1_000_000L), n / 1000);
                case "nanoseconds" -> Math.addExact(Math.multiplyExact(s, NANOS_PER_SECOND), n);
                case "quartersOfYear" -> (m % 12) / 3;
                case "monthsOfQuarter" -> m % 3;
                case "monthsOfYear" -> m % 12;
                case "daysOfWeek" -> dd % 7;
                case "minutesOfHour" -> (ts / 60) % 60;
                case "secondsOfMinute" -> ts % 60;
                case "millisecondsOfSecond" -> n / 1_000_000;
                case "microsecondsOfSecond" -> n / 1000;
                case "nanosecondsOfSecond" -> n;
                default -> null;
            };
        }
        LocalDate d = baseDate(x);
        LocalTime t = baseTime(x);
        ZoneOffset off = x instanceof OffsetTime ot ? ot.getOffset() : x instanceof ZonedDateTime z ? z.getOffset() : null;
        return switch (k) {
            case "year" -> d == null ? null : (Object) (long) d.getYear();
            case "quarter" -> d == null ? null : (Object) (long) d.get(IsoFields.QUARTER_OF_YEAR);
            case "month" -> d == null ? null : (Object) (long) d.getMonthValue();
            case "week" -> d == null ? null : (Object) (long) d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "weekYear" -> d == null ? null : (Object) (long) d.get(IsoFields.WEEK_BASED_YEAR);
            case "day" -> d == null ? null : (Object) (long) d.getDayOfMonth();
            case "ordinalDay" -> d == null ? null : (Object) (long) d.getDayOfYear();
            case "dayOfWeek", "weekDay" -> d == null ? null : (Object) (long) d.getDayOfWeek().getValue();
            case "dayOfQuarter" -> d == null ? null : (Object) (long) d.get(IsoFields.DAY_OF_QUARTER);
            case "hour" -> t == null ? null : (Object) (long) t.getHour();
            case "minute" -> t == null ? null : (Object) (long) t.getMinute();
            case "second" -> t == null ? null : (Object) (long) t.getSecond();
            case "millisecond" -> t == null ? null : (Object) (long) (t.getNano() / 1_000_000);
            case "microsecond" -> t == null ? null : (Object) (long) (t.getNano() / 1000);
            case "nanosecond" -> t == null ? null : (Object) (long) t.getNano();
            case "timezone" -> x instanceof ZonedDateTime z ? z.getZone().toString() : off != null ? off.toString() : null;
            case "offset" -> off == null ? null : off.toString();
            case "offsetMinutes" -> off == null ? null : (Object) (long) (off.getTotalSeconds() / 60);
            case "offsetSeconds" -> off == null ? null : (Object) (long) off.getTotalSeconds();
            case "epochMillis" -> x instanceof ZonedDateTime z ? (Object) z.toInstant().toEpochMilli() : null;
            case "epochSeconds" -> x instanceof ZonedDateTime z ? (Object) z.toInstant().getEpochSecond() : null;
            default -> null;
        };
    }

    // ═══ 运算 ═══

    /** 时间点 + duration（月 → 日 → 秒依次加；date 只加月与日，时间部分忽略）。不是时间点 ⇒ null。 */
    public static Object plus(Object x, Dur d) {
        return switch (x) {
            case LocalDate v -> v.plusMonths(d.months()).plusDays(d.days() + truncSeconds(d) / 86400);
            case LocalTime v -> v.plusSeconds(d.seconds()).plusNanos(d.nanos());
            case OffsetTime v -> v.plusSeconds(d.seconds()).plusNanos(d.nanos());
            case LocalDateTime v -> v.plusMonths(d.months()).plusDays(d.days()).plusSeconds(d.seconds()).plusNanos(d.nanos());
            case ZonedDateTime v -> v.plusMonths(d.months()).plusDays(d.days()).plusSeconds(d.seconds()).plusNanos(d.nanos());
            case null, default -> null;
        };
    }

    private record Parts(LocalDate date, LocalTime time, ZoneId zone) {
    }

    private static Parts parts(Object x) {
        return switch (x) {
            case LocalDate d -> new Parts(d, null, null);
            case LocalTime t -> new Parts(null, t, null);
            case OffsetTime t -> new Parts(null, t.toLocalTime(), t.getOffset());
            case LocalDateTime t -> new Parts(t.toLocalDate(), t.toLocalTime(), null);
            case ZonedDateTime z -> new Parts(z.toLocalDate(), z.toLocalTime(), z.getZone());
            case null, default -> throw fail("duration.between needs temporal values");
        };
    }

    public static Dur between(Object a, Object b) {
        return between(a, b, BetweenMode.ALL);
    }

    /**
     * duration.between(a, b)（同 Neo4j）：只有一边带时区 ⇒ 另一边按这个时区理解；两边都带 ⇒ b 换算到 a 的时区；
     * 有一边没有日期（time / localtime）⇒ 只算时间差（借另一边的日期）。月 → 日 → 秒依次取整。
     */
    public static Dur between(Object a, Object b, BetweenMode mode) {
        Parts pa = parts(a), pb = parts(b);
        ZoneId za = or(pa.zone, or(pb.zone, ZoneOffset.UTC)), zb = or(pb.zone, or(pa.zone, ZoneOffset.UTC));
        boolean timeOnly = pa.date == null || pb.date == null;
        LocalDate day = or(pa.date, or(pb.date, LocalDate.of(1970, 1, 1)));
        ZonedDateTime x = ZonedDateTime.of(timeOnly ? day : pa.date, or(pa.time, LocalTime.MIDNIGHT), za);
        ZonedDateTime y = ZonedDateTime.of(timeOnly ? day : pb.date, or(pb.time, LocalTime.MIDNIGHT), zb).withZoneSameInstant(za);
        if (timeOnly) return mode == BetweenMode.MONTHS || mode == BetweenMode.DAYS ? dur(0, 0, 0, 0) : secs(x, y);
        return switch (mode) {
            case MONTHS -> dur(ChronoUnit.MONTHS.between(x, y), 0, 0, 0);
            case DAYS -> dur(0, ChronoUnit.DAYS.between(x, y), 0, 0);
            case SECONDS -> secs(x, y);
            case ALL -> {
                long mo = ChronoUnit.MONTHS.between(x, y);
                ZonedDateTime x1 = x.plusMonths(mo);
                long dd = ChronoUnit.DAYS.between(x1, y);
                Dur r = secs(x1.plusDays(dd), y);
                yield dur(mo, dd, r.seconds(), r.nanos());
            }
        };
    }

    private static Dur secs(ZonedDateTime p, ZonedDateTime q) {
        Duration d = Duration.between(p, q);
        return dur(0, 0, d.getSeconds(), d.getNano());
    }

    /** 同类时间点比较 ⇒ 负 / 0 / 正；不同类 / duration ⇒ null。datetime 与 time 按时刻比。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Integer compare(Object a, Object b) {
        if (a instanceof ZonedDateTime x && b instanceof ZonedDateTime y) return x.toInstant().compareTo(y.toInstant());
        if (a instanceof OffsetTime x && b instanceof OffsetTime y)
            return x.withOffsetSameInstant(ZoneOffset.UTC).compareTo(y.withOffsetSameInstant(ZoneOffset.UTC));
        if (a != null && b != null && !(a instanceof Dur) && a.getClass() == b.getClass() && a instanceof Comparable c)
            return c.compareTo(b);
        return null;
    }

    /** 列表里的时间值是不是同一类（图层 {@code _types} 的 {@code list:类型} 用）。 */
    public static boolean sameType(List<?> xs) {
        if (xs.isEmpty() || !isTemporal(xs.getFirst())) return false;
        String t = typeName(xs.getFirst());
        for (Object x : xs) if (!t.equals(typeName(x))) return false;
        return true;
    }
}
