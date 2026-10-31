package com.zcshou.track;

/**
 * 轨迹采样点（WGS84 坐标）。
 * dt 为该点与上一个点之间的时间间隔（毫秒），第一个点为 0。
 */
public class TrackPoint {
    public final double lat;
    public final double lng;
    public final double alt;
    public final float bea;
    public final int dt;

    public TrackPoint(double lat, double lng, double alt, float bea, int dt) {
        this.lat = lat;
        this.lng = lng;
        this.alt = alt;
        this.bea = bea;
        this.dt = dt;
    }
}