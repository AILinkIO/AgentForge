package io.ailink.agentforge.bitcask.value;

import java.util.Locale;
import java.util.Map;

/**
 * 空间类型 Point（同 bitcask：{@code point({x, y[, z]})} / {@code point({longitude, latitude[, height]})}，坐标系 SRID
 * 7203 cartesian、9157 cartesian-3d、4326 wgs-84、4979 wgs-84-3d）。距离：笛卡尔是欧氏距离，WGS-84 是大圆距离（米，3D 再计入高度差）；
 * withinBBox（WGS-84 下左下经度大于右上时按跨日界线）。存储文本 {@code srid;x;y[;z]}，图层 {@code _types} 里类型记 {@code point}
 * （与 bitcask 一致）。
 *
 * @param z 第三维（2D ⇒ null）
 */
public record Point(long srid, double x, double y, Double z) {
    private static final double EARTH_RADIUS = 6378140.0;

    public static boolean isGeographic(long srid) {
        return srid == 4326 || srid == 4979;
    }

    /** SRID ⇒ 坐标系名；不认识 ⇒ null。 */
    public static String crsName(long srid) {
        return switch ((int) srid) {
            case 7203 -> "cartesian";
            case 9157 -> "cartesian-3d";
            case 4326 -> "wgs-84";
            case 4979 -> "wgs-84-3d";
            default -> null;
        };
    }

    private static Long crsToSrid(String crs) {
        return switch (crs.toLowerCase(Locale.ROOT)) {
            case "cartesian" -> 7203L;
            case "cartesian-3d" -> 9157L;
            case "wgs-84" -> 4326L;
            case "wgs-84-3d" -> 4979L;
            default -> null;
        };
    }

    /** point(map)：任一给出的坐标为 null ⇒ null。 */
    public static Point construct(Map<String, ?> m) {
        if (m == null) throw new ValueException("point() needs a map");
        boolean geoKeys = m.containsKey("longitude") || m.containsKey("latitude");
        Object x = m.get(geoKeys ? "longitude" : "x"), y = m.get(geoKeys ? "latitude" : "y"), z = m.get(geoKeys ? "height" : "z");
        Object crs = m.get("crs"), srid = m.get("srid");
        for (String k : new String[]{"x", "y", "z", "longitude", "latitude", "height"}) {
            if (m.containsKey(k) && m.get(k) == null) return null;
        }
        if (!(x instanceof Number) || !(y instanceof Number))
            throw new ValueException("point() needs x and y (or longitude and latitude) as numbers");
        if (z != null && !(z instanceof Number)) throw new ValueException("point() coordinates must be numbers");
        boolean three = z != null;
        long s;
        if (Frac.isIntegral(srid)) s = ((Number) srid).longValue();
        else if (crs instanceof String c) {
            Long v = crsToSrid(c);
            if (v == null) throw new ValueException("unknown crs \"" + c + "\"");
            s = v;
        } else if (geoKeys) s = three ? 4979 : 4326;
        else s = three ? 9157 : 7203;
        if (crsName(s) == null) throw new ValueException("unknown srid " + s);
        if (three != (s == 9157 || s == 4979)) throw new ValueException("point() dimension does not match the crs");
        return new Point(s, ((Number) x).doubleValue(), ((Number) y).doubleValue(), three ? ((Number) z).doubleValue() : null);
    }

    /** 字段（x / y / z / longitude / latitude / height / srid / crs）；没有 ⇒ null。 */
    public Object field(String k) {
        boolean geo = isGeographic(srid);
        return switch (k) {
            case "x" -> x;
            case "y" -> y;
            case "z" -> z;
            case "longitude" -> geo ? x : null;
            case "latitude" -> geo ? y : null;
            case "height" -> geo ? z : null;
            case "srid" -> srid;
            case "crs" -> crsName(srid);
            default -> null;
        };
    }

    /** 两点距离；坐标系不同 ⇒ null。 */
    public static Double distance(Point a, Point b) {
        if (a.srid != b.srid) return null;
        if (isGeographic(a.srid)) {
            double lat1 = Math.toRadians(a.y), lat2 = Math.toRadians(b.y);
            double dlat = lat2 - lat1, dlon = Math.toRadians(b.x) - Math.toRadians(a.x);
            double h = Math.pow(Math.sin(dlat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dlon / 2), 2);
            double d = 2 * EARTH_RADIUS * Math.asin(Math.min(1.0, Math.sqrt(h)));
            return a.z != null ? Math.sqrt(d * d + Math.pow(b.z - a.z, 2)) : d;
        }
        return Math.sqrt(Math.pow(b.x - a.x, 2) + Math.pow(b.y - a.y, 2) + (a.z != null ? Math.pow(b.z - a.z, 2) : 0.0));
    }

    /** point.withinBBox(p, 左下, 右上)；坐标系不同 ⇒ null。 */
    public static Boolean withinBBox(Point p, Point ll, Point ur) {
        if (p.srid != ll.srid || p.srid != ur.srid) return null;
        boolean latOk = ll.y <= p.y && p.y <= ur.y;
        boolean lonOk = isGeographic(p.srid) && ll.x > ur.x ? (p.x >= ll.x || p.x <= ur.x) : (ll.x <= p.x && p.x <= ur.x);
        return latOk && lonOk;
    }

    /** 存储文本 {@code srid;x;y[;z]}。 */
    public String toText() {
        return srid + ";" + x + ";" + y + (z != null ? ";" + z : "");
    }

    public static Point parseText(String s) {
        String[] p = s.split(";");
        return new Point(Long.parseLong(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), p.length > 3 ? Double.parseDouble(p[3]) : null);
    }

    @Override
    public String toString() {
        String crs = crsName(srid) != null ? crsName(srid) : String.valueOf(srid);
        boolean geo = isGeographic(srid);
        return "point({" + (geo ? "longitude" : "x") + ": " + x + ", " + (geo ? "latitude" : "y") + ": " + y
                + (z != null ? ", " + (geo ? "height" : "z") + ": " + z : "") + ", crs: '" + crs + "'})";
    }
}
