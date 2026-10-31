package com.zcshou.track;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 轨迹录制 / 回放的核心。
 * <p>
 * 录制：由定位服务每个刷新周期调用 {@link #sample(double, double, double, float)} 采样当前位置，
 * 只在移动超过阈值（或静止超过一段时间）时落点，保证点的数量可控且能还原真实节奏。
 * 回放：在后台线程里按记录的时间轴插值，把位置推给 {@link PositionSink}（也就是定位服务），
 * 效果和手动拖摇杆移动一样，只是自动执行，并且可以循环、可调速。
 */
public class TrackManager {
    private static final String TAG = "TrackManager";

    public interface PositionSink {
        /** @param speed 该段速度（米/秒），用于回放时上报和轨迹一致的速度 */
        void onTrackPosition(double lng, double lat, double alt, float bea, float speed);
    }

    public interface Listener {
        /** 录制状态变化（每 0.5s 也会回调一次用于刷新界面） */
        void onRecordState(boolean recording, int points, double meters, long durationMs);

        /** 回放状态变化 */
        void onPlayState(boolean playing, boolean paused, String trackName, int percent);

        /** 轨迹列表变化（新增 / 删除） */
        void onTracksChanged();
    }

    /** 位移超过该距离（米）就记录一个点 */
    private static final double SAMPLE_MIN_MOVE_M = 0.6;
    /** 静止时至少间隔该时间（毫秒）记录一个点，用来保留停留时长 */
    private static final long SAMPLE_MAX_IDLE_MS = 10000L;
    /** 回放时单点最大时间间隔，避免录到的长时间停留让回放“卡住”太久 */
    private static final int MAX_DT_MS = 10000;
    private static final long UI_INTERVAL_MS = 500L;

    private final Context mContext;
    private final TrackStore mStore;
    private final PositionSink mSink;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private Listener mListener;

    /* ---------------- 录制状态 ---------------- */
    private boolean mRecording = false;
    private final List<TrackPoint> mBuf = new ArrayList<>();
    private TrackPoint mLastPoint;
    private long mLastSampleAt = 0L;
    private long mRecordStart = 0L;
    private double mDistance = 0.0;
    private long mLastRecordUi = 0L;

    /* ---------------- 回放状态 ---------------- */
    private volatile boolean mPlaying = false;
    private volatile boolean mPaused = false;
    private volatile boolean mLoop = false;
    private volatile float mSpeedFactor = 1.0f;
    private volatile boolean mStopRequested = false;
    private volatile int mPlayPercent = 0;
    private Thread mPlayThread;
    private Track mPlayTrack;
    /** 仅用于日志抽样，判断回放是否真的在推进 */
    private int mPlayTickLogs = 0;

    public TrackManager(Context context, PositionSink sink) {
        mContext = context.getApplicationContext();
        mStore = new TrackStore(mContext);
        mSink = sink;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public List<Track> listTracks() {
        return mStore.list();
    }

    /** 重命名轨迹 */
    public boolean rename(Track track, String name) {
        if (track == null || name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        track.name = trimmed;
        boolean ok = mStore.save(track);
        if (ok) {
            postTracksChanged();
        }
        return ok;
    }

    /** 只把某条轨迹显示在地图上（不启动回放） */
    public void previewTrack(Track track) {
        if (track != null) {
            publishActive(track.points);
        }
    }

    /** 导出目录：外置存储的应用目录，方便用数据线把备份拷走 */
    public File exportDir() {
        File dir = mContext.getExternalFilesDir(null);
        if (dir == null) {
            dir = new File(mContext.getFilesDir(), "export");
        }
        return new File(dir, "tracks-export");
    }

    /** 导出全部轨迹，返回备份文件（失败返回 null） */
    public File exportTracks() {
        List<Track> tracks = mStore.list();
        if (tracks.isEmpty()) {
            return null;
        }
        String name = "tracks-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
                .format(new Date()) + ".json";
        return mStore.exportAll(tracks, exportDir(), name);
    }

    /** 从导出目录里的所有备份文件导入，返回导入条数 */
    public int importTracks() {
        File dir = exportDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) {
            return -1;
        }
        int total = 0;
        for (File f : files) {
            total += mStore.importFrom(f);
        }
        if (total > 0) {
            postTracksChanged();
        }
        return total;
    }

    public boolean isRecording() {
        return mRecording;
    }

    public boolean isPlaying() {
        return mPlaying;
    }

    public boolean isPaused() {
        return mPaused;
    }

    public boolean isLoop() {
        return mLoop;
    }

    public void setLoop(boolean loop) {
        mLoop = loop;
    }

    public float getSpeedFactor() {
        return mSpeedFactor;
    }

    public void setSpeedFactor(float factor) {
        if (factor < 0.1f) {
            factor = 0.1f;
        }
        if (factor > 16.0f) {
            factor = 16.0f;
        }
        mSpeedFactor = factor;
    }

    /** 当前要在图上显示的轨迹点（录制中=已走过的点，回放/选中=整条轨迹），供地图层画折线 */
    private static volatile List<TrackPoint> sActivePoints = new ArrayList<>();

    public static List<TrackPoint> activePoints() {
        return sActivePoints;
    }

    private static void publishActive(List<TrackPoint> points) {
        synchronized (TrackManager.class) {
            sActivePoints = (points == null) ? new ArrayList<>() : new ArrayList<>(points);
        }
    }

    /* ==================== 录制 ==================== */

    public void startRecord() {
        if (mRecording) {
            return;
        }
        stopPlayback();
        synchronized (this) {
            mBuf.clear();
            mLastPoint = null;
            mLastSampleAt = 0L;
            mDistance = 0.0;
            mRecordStart = SystemClock.elapsedRealtime();
            mRecording = true;
        }
        mLastRecordUi = 0L;
        Log.d(TAG, "startRecord");
        publishActive(null);
        postRecordState();
    }

    /**
     * 停止录制并保存。
     *
     * @param name 轨迹名称，传 null 时自动按时间命名
     * @return 成功保存的轨迹，点太少（<2）时返回 null
     */
    public Track stopRecord(String name) {
        if (!mRecording) {
            return null;
        }
        List<TrackPoint> snapshot;
        synchronized (this) {
            mRecording = false;
            snapshot = new ArrayList<>(mBuf);
        }
        if (snapshot.size() < 2) {
            Log.d(TAG, "discard track, points=" + snapshot.size());
            publishActive(null);
            postRecordState();
            return null;
        }

        Track track = new Track();
        track.created = System.currentTimeMillis();
        track.name = (name == null || name.trim().isEmpty())
                ? "轨迹 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(track.created))
                : name.trim();
        track.points.addAll(snapshot);

        boolean ok = mStore.save(track);
        postRecordState();
        if (ok) {
            /* 存完后继续把这条线留在图上 */
            publishActive(track.points);
            Log.d(TAG, "saved track " + track.name + " points=" + track.points.size()
                    + " meters=" + (int) track.distanceMeters() + " duration=" + track.durationMs());
            postTracksChanged();
            return track;
        }
        Log.e(TAG, "save track failed");
        return null;
    }

    /** 录制中的实时状态（点数、距离、时长） */
    public int recordPoints() {
        synchronized (this) {
            return mBuf.size();
        }
    }

    public double recordMeters() {
        synchronized (this) {
            return mDistance;
        }
    }

    public long recordDurationMs() {
        if (!mRecording) {
            return 0L;
        }
        return SystemClock.elapsedRealtime() - mRecordStart;
    }

    /**
     * 定位服务每个刷新周期调用一次，把当前模拟位置送进录制裁剪器。
     */
    public void sample(double lat, double lng, double alt, float bea) {
        if (!mRecording) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (!mRecording) {
                return;
            }
            TrackPoint last = mLastPoint;
            if (last == null) {
                mBuf.add(new TrackPoint(lat, lng, alt, bea, 0));
                mLastPoint = mBuf.get(0);
                mLastSampleAt = now;
            } else {
                double d = Track.distance(last.lat, last.lng, lat, lng);
                long idle = now - mLastSampleAt;
                if (d < SAMPLE_MIN_MOVE_M && idle < SAMPLE_MAX_IDLE_MS) {
                    return;
                }
                int dt = (int) Math.min(MAX_DT_MS, Math.max(1L, idle));
                mDistance += d;
                TrackPoint point = new TrackPoint(lat, lng, alt, bea, dt);
                mBuf.add(point);
                mLastPoint = point;
                mLastSampleAt = now;
            }
        }
        synchronized (this) {
            publishActive(mBuf);
        }
        if (now - mLastRecordUi >= UI_INTERVAL_MS) {
            mLastRecordUi = now;
            postRecordState();
        }
    }

    /* ==================== 回放 ==================== */

    public void play(Track track, boolean loop) {
        if (track == null || track.points.size() < 2) {
            return;
        }
        stopPlayback();
        if (mRecording) {
            stopRecord(null);
        }
        mPlayTrack = track;
        mLoop = loop;
        publishActive(track.points);
        mPaused = false;
        mStopRequested = false;
        mPlaying = true;
        mPlayPercent = 0;
        mPlayTickLogs = 0;

        mPlayThread = new Thread(() -> runPlayback(track), "TrackPlayer");
        mPlayThread.setDaemon(true);
        mPlayThread.start();
        Log.d(TAG, "play " + track.name + " points=" + track.points.size()
                + " loop=" + loop + " speed=" + mSpeedFactor);
        postPlayState();
    }

    public void pausePlayback() {
        if (mPlaying) {
            mPaused = true;
            postPlayState();
        }
    }

    public void resumePlayback() {
        if (mPlaying) {
            mPaused = false;
            postPlayState();
        }
    }

    public void stopPlayback() {
        if (!mPlaying && mPlayThread == null) {
            return;
        }
        mStopRequested = true;
        mPlaying = false;
        Thread thread = mPlayThread;
        mPlayThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        mPlayTrack = null;
        mPlayPercent = 0;
        Log.d(TAG, "stopPlayback");
        postPlayState();
    }

    /** 服务销毁时调用：停止回放，正在录制的轨迹自动保存 */
    public void release() {
        stopPlayback();
        if (mRecording) {
            stopRecord(null);
        }
        mListener = null;
    }

    public boolean deleteTrack(Track track) {
        boolean ok = mStore.delete(track);
        if (ok) {
            postTracksChanged();
        }
        return ok;
    }

    private void runPlayback(Track track) {
        final int n = track.points.size();
        final long[] cum = new long[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + Math.max(0, track.points.get(i).dt);
        }
        long total = Math.max(1L, cum[n - 1]);
        int idx = 0;
        long virtualTime = 0L;
        long lastTick = SystemClock.elapsedRealtime();
        long lastUi = 0L;
        float lastSpeed = 1.2f;

        while (!mStopRequested) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = SystemClock.elapsedRealtime();
            long delta = now - lastTick;
            lastTick = now;
            if (mStopRequested) {
                break;
            }
            if (mPaused) {
                continue;
            }

            virtualTime += (long) (delta * mSpeedFactor);
            mPlayPercent = (int) Math.min(100L, virtualTime * 100L / total);

            while (idx + 1 < n && cum[idx + 1] <= virtualTime) {
                idx++;
            }

            if (virtualTime >= total) {
                TrackPoint end = track.points.get(n - 1);
                pushPosition(end.lng, end.lat, end.alt, end.bea, lastSpeed);
                if (mLoop && !mStopRequested) {
                    virtualTime = 0L;
                    idx = 0;
                    mPlayPercent = 0;
                    lastTick = SystemClock.elapsedRealtime();
                    postPlayState();
                    continue;
                }
                break;
            }

            TrackPoint a = track.points.get(idx);
            TrackPoint b = track.points.get(idx + 1);
            long seg = cum[idx + 1] - cum[idx];
            double alpha = seg <= 0 ? 1.0 : (double) (virtualTime - cum[idx]) / (double) seg;
            double lat = a.lat + (b.lat - a.lat) * alpha;
            double lng = a.lng + (b.lng - a.lng) * alpha;
            double alt = a.alt + (b.alt - a.alt) * alpha;
            /* 用这一段的两点距离/时长反推速度，回放时上报给定位服务 */
            double segMeters = Track.distance(a.lat, a.lng, b.lat, b.lng);
            float speed = seg <= 0 ? lastSpeed : (float) (segMeters * 1000.0 / seg);
            if (speed > 0.2f && speed < 40f) {
                lastSpeed = speed;
            }
            pushPosition(lng, lat, alt, Track.bearing(a.lat, a.lng, b.lat, b.lng), lastSpeed);

            if (mPlayTickLogs++ % 40 == 0) {
                Log.d(TAG, "playback tick idx=" + idx + "/" + n + " pct=" + mPlayPercent
                        + " lat=" + lat + " lng=" + lng);
            }

            if (now - lastUi >= UI_INTERVAL_MS) {
                lastUi = now;
                postPlayState();
            }
        }

        if (mPlaying) {
            mPlaying = false;
            mPlayTrack = null;
            Log.d(TAG, "playback finished, ticks=" + mPlayTickLogs + " pct=" + mPlayPercent);
            postPlayState();
        }
    }

    private void pushPosition(double lng, double lat, double alt, float bea, float speed) {
        if (mSink == null) {
            return;
        }
        try {
            mSink.onTrackPosition(lng, lat, alt, bea, speed);
        } catch (Exception e) {
            Log.e(TAG, "push position failed", e);
        }
    }

    /* ==================== 状态回调 ==================== */

    private void postRecordState() {
        final boolean recording = mRecording;
        final int points = recordPoints();
        final double meters = recordMeters();
        final long duration = recording ? (SystemClock.elapsedRealtime() - mRecordStart) : 0L;
        mMainHandler.post(() -> {
            Listener l = mListener;
            if (l != null) {
                l.onRecordState(recording, points, meters, duration);
            }
        });
    }

    private void postPlayState() {
        final boolean playing = mPlaying;
        final boolean paused = mPaused;
        final Track track = mPlayTrack;
        final String name = track == null ? "" : track.name;
        final int percent = mPlayPercent;
        mMainHandler.post(() -> {
            Listener l = mListener;
            if (l != null) {
                l.onPlayState(playing, paused, name, percent);
            }
        });
    }

    private void postTracksChanged() {
        mMainHandler.post(() -> {
            Listener l = mListener;
            if (l != null) {
                l.onTracksChanged();
            }
        });
    }
}