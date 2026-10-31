package com.zcshou.track;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.zcshou.gogogo.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 轨迹列表适配器（每行：名称 + 摘要 + 播放按钮 + 删除按钮）。
 */
public class TrackAdapter extends BaseAdapter {

    public interface Callback {
        void onPlay(Track track);

        void onDelete(Track track);

        void onRename(Track track);

        void onShowOnMap(Track track);
    }

    private final LayoutInflater mInflater;
    private final List<Track> mData = new ArrayList<>();
    private final Callback mCallback;

    public TrackAdapter(Context context, Callback callback) {
        this.mInflater = LayoutInflater.from(context);
        this.mCallback = callback;
    }

    public void setData(List<Track> data) {
        mData.clear();
        if (data != null) {
            mData.addAll(data);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mData.size();
    }

    @Override
    public Track getItem(int position) {
        return mData.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = mInflater.inflate(R.layout.track_item, parent, false);
        }
        final Track track = mData.get(position);

        TextView name = view.findViewById(R.id.track_item_name);
        TextView info = view.findViewById(R.id.track_item_info);
        ImageButton play = view.findViewById(R.id.track_item_play);
        ImageButton delete = view.findViewById(R.id.track_item_delete);
        ImageButton showMap = view.findViewById(R.id.track_item_map);

        name.setText(track.name);
        info.setText(summary(track));
        showMap.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onShowOnMap(track);
            }
        });
        play.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onPlay(track);
            }
        });
        delete.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onDelete(track);
            }
        });
        view.setOnLongClickListener(v -> {
            if (mCallback != null) {
                mCallback.onRename(track);
                return true;
            }
            return false;
        });
        return view;
    }

    public static String summary(Track track) {
        long ms = track.durationMs();
        long sec = ms / 1000L;
        String duration = sec >= 3600
                ? String.format(Locale.getDefault(), "%d 时 %d 分", sec / 3600, (sec % 3600) / 60)
                : String.format(Locale.getDefault(), "%d 分 %d 秒", sec / 60, sec % 60);

        double meters = track.distanceMeters();
        String distance = meters >= 1000
                ? String.format(Locale.getDefault(), "%.2f km", meters / 1000)
                : String.format(Locale.getDefault(), "%.0f m", meters);

        String date = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(track.created));

        return String.format(Locale.getDefault(), "%s · %s · %d 点 · %s",
                duration, distance, track.points.size(), date);
    }
}