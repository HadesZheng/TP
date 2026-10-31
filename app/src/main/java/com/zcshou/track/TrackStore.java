package com.zcshou.track;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 轨迹文件存储：每条轨迹一个 JSON 文件，放在应用私有目录 files/tracks 下。
 * 覆盖安装 / 升级应用不会清空该目录，所以轨迹可以长期保留、反复使用。
 */
public class TrackStore {
    private static final String TAG = "TrackStore";
    private static final String DIR_NAME = "tracks";

    private final File mDir;

    public TrackStore(Context ctx) {
        mDir = new File(ctx.getFilesDir(), DIR_NAME);
        if (!mDir.exists() && !mDir.mkdirs()) {
            Log.e(TAG, "can not create dir: " + mDir);
        }
    }

    public File getDir() {
        return mDir;
    }

    public List<Track> list() {
        List<Track> ret = new ArrayList<>();
        File[] files = mDir.listFiles();
        if (files == null) {
            return ret;
        }
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) {
                continue;
            }
            Track t = load(f);
            if (t != null && t.points.size() >= 2) {
                ret.add(t);
            }
        }
        Collections.sort(ret, (a, b) -> Long.compare(b.created, a.created));
        return ret;
    }

    public boolean save(Track track) {
        if (track == null || track.points.size() < 2) {
            return false;
        }
        if (track.id == null || track.id.isEmpty()) {
            track.id = "t" + System.currentTimeMillis();
        }
        File tmp = new File(mDir, track.id + ".json.tmp");
        File dst = new File(mDir, track.id + ".json");
        Writer writer = null;
        try {
            JSONArray points = new JSONArray();
            for (TrackPoint p : track.points) {
                JSONArray item = new JSONArray();
                item.put(p.lat);
                item.put(p.lng);
                item.put(p.alt);
                item.put((double) p.bea);
                item.put(p.dt);
                points.put(item);
            }
            JSONObject obj = new JSONObject();
            obj.put("name", track.name);
            obj.put("created", track.created);
            obj.put("points", points);

            FileOutputStream fos = new FileOutputStream(tmp);
            writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write(obj.toString());
            writer.flush();
            writer.close();
            writer = null;

            if (dst.exists() && !dst.delete()) {
                Log.e(TAG, "delete old track failed");
            }
            return tmp.renameTo(dst);
        } catch (Exception e) {
            Log.e(TAG, "save track failed", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean delete(Track track) {
        if (track == null || track.id == null || track.id.isEmpty()) {
            return false;
        }
        File f = new File(mDir, track.id + ".json");
        return !f.exists() || f.delete();
    }

    /** 把若干条轨迹写成一个 JSON 备份文件 */
    public File exportAll(List<Track> tracks, File dir, String fileName) {
        if (dir == null || tracks == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "can not create export dir: " + dir);
            return null;
        }
        File dst = new File(dir, fileName);
        Writer writer = null;
        try {
            JSONArray arr = new JSONArray();
            for (Track t : tracks) {
                arr.put(toJson(t));
            }
            JSONObject root = new JSONObject();
            root.put("app", "gogogo-track");
            root.put("version", 1);
            root.put("exported", System.currentTimeMillis());
            root.put("tracks", arr);

            writer = new OutputStreamWriter(new FileOutputStream(dst), StandardCharsets.UTF_8);
            writer.write(root.toString());
            writer.flush();
            return dst;
        } catch (Exception e) {
            Log.e(TAG, "export tracks failed", e);
            return null;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 从备份文件导入轨迹，返回成功导入的条数；重复导入不会覆盖已有轨迹 */
    public int importFrom(File file) {
        int count = 0;
        try {
            JSONObject root = new JSONObject(readAll(file));
            JSONArray arr = root.optJSONArray("tracks");
            if (arr == null) {
                /* 单条轨迹格式 */
                Track one = fromJson(root);
                if (one != null && save(one)) {
                    count++;
                }
                return count;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                Track t = fromJson(obj);
                if (t != null && save(t)) {
                    count++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "import tracks failed: " + file.getName(), e);
        }
        return count;
    }

    private JSONObject toJson(Track track) throws Exception {
        JSONArray points = new JSONArray();
        for (TrackPoint p : track.points) {
            JSONArray item = new JSONArray();
            item.put(p.lat);
            item.put(p.lng);
            item.put(p.alt);
            item.put((double) p.bea);
            item.put(p.dt);
            points.put(item);
        }
        JSONObject obj = new JSONObject();
        obj.put("name", track.name);
        obj.put("created", track.created);
        obj.put("points", points);
        return obj;
    }

    private Track fromJson(JSONObject obj) {
        JSONArray points = obj.optJSONArray("points");
        if (points == null || points.length() < 2) {
            return null;
        }
        Track track = new Track();
        long created = obj.optLong("created", 0L);
        track.created = created > 0 ? created : System.currentTimeMillis();
        String name = obj.optString("name", "");
        track.name = name.isEmpty()
                ? "导入轨迹 " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(new Date(track.created))
                : name;
        track.id = "ti" + track.created + "_" + (int) (Math.random() * 100000);
        for (int i = 0; i < points.length(); i++) {
            JSONArray item = points.optJSONArray(i);
            if (item == null || item.length() < 5) {
                continue;
            }
            track.points.add(new TrackPoint(
                    item.optDouble(0), item.optDouble(1), item.optDouble(2),
                    (float) item.optDouble(3), item.optInt(4)));
        }
        return track.points.size() >= 2 ? track : null;
    }

    private String readAll(File f) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private Track load(File f) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject obj = new JSONObject(sb.toString());

            Track track = new Track();
            track.id = f.getName().substring(0, f.getName().length() - ".json".length());
            track.name = obj.optString("name", track.id);
            track.created = obj.optLong("created", 0L);

            JSONArray points = obj.optJSONArray("points");
            if (points != null) {
                for (int i = 0; i < points.length(); i++) {
                    JSONArray item = points.optJSONArray(i);
                    if (item == null || item.length() < 5) {
                        continue;
                    }
                    track.points.add(new TrackPoint(
                            item.optDouble(0), item.optDouble(1), item.optDouble(2),
                            (float) item.optDouble(3), item.optInt(4)));
                }
            }
            return track;
        } catch (Exception e) {
            Log.e(TAG, "load track failed: " + f.getName(), e);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}