package com.flying.whitefox.ui.dashboard;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.flying.whitefox.R;
import com.flying.whitefox.data.model.music.PlaylistData;

import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.SongViewHolder> {
    private List<PlaylistData.Song> songs;
    private int currentSongIndex;
    private OnSongClickListener listener;

    public interface OnSongClickListener {
        void onSongClick(PlaylistData.Song song, int position);
    }

    public PlaylistAdapter(List<PlaylistData.Song> songs, int currentSongIndex, OnSongClickListener listener) {
        this.songs = songs;
        this.currentSongIndex = currentSongIndex;
        this.listener = listener;
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_songplaylist, parent, false);
        return new SongViewHolder(view);
    }

    @SuppressLint("ResourceAsColor")
    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        PlaylistData.Song song = songs.get(position);
        holder.bind(song, position);
        
        // 设置点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSongClick(song, position);
            }
        });
        
        // 高亮显示当前播放的歌曲
        if (position == currentSongIndex) {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.getContext(), R.color.current_playing_background)
            );
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    @Override
    public int getItemCount() {
        return songs != null ? songs.size() : 0;
    }
    
    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView songTitle;
        TextView songArtist;
        // 其他视图组件
        
        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            songTitle = itemView.findViewById(R.id.tv_song_name);
            songArtist = itemView.findViewById(R.id.tv_song_artist);
            // 初始化其他视图组件
        }
        
        public void bind(PlaylistData.Song song, int position) {
            songTitle.setText(song.getName());
            songArtist.setText(song.getAr_name());
            // 绑定其他数据
        }
    }
}
