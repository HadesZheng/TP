package com.zcshou.track;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条可重复回放的移动轨迹。
 */
public class Track {
    /** 存储文件名（不含扩展名），由 TrackStore 生成 */
    public String id = "";
    /** 显示名称 */
    public String name = "";
    /** 记录时间戳（毫秒） */
    public long created = 0L;
    /** 轨迹点（按时间顺序） */
    public final List<TrackPoint> points = new ArrayList<>();

    public long durationMs() {
        long ms = 0L;
        for (int i = 1; i < points.size(); i++) {
            ms += Math.max(0, points.get(i).dt);
        }
        return ms;
    }

    public double distanceMeters() {
        double d = 0.0;
        for (int i = 1; i < points.size(); i++) {
            TrackPoint a = points.get(i - 1);
            TrackPoint b = points.get(i);
            d += distance(a.lat, a.lng, b.lat, b.lng);
        }
        return d;
    }

    /** 两点间距离（米），Haversine 公式 */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** 起始点方向角（度） */
    public static float bearing(double lat1, double lng1, double lat2, double lng2) {
        double dLng = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLng) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLng);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (float) ((deg + 360.0) % 360.0);
    }
}