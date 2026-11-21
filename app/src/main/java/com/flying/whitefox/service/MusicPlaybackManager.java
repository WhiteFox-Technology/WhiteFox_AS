package com.flying.whitefox.service;

import static java.lang.Thread.sleep;

import android.content.Context;
import android.util.Log;

import com.flying.whitefox.data.model.music.PlaylistData;
import com.flying.whitefox.data.model.music.PlayMode;

import snow.player.PlayerClient;
import snow.player.audio.MusicItem;
import snow.player.playlist.Playlist;

public class MusicPlaybackManager {

    public interface PlaybackStateListener {
        void onPlaybackStateChanged(PlaybackState state);

        void onPlaylistUpdated(PlaylistData playlist);

        void onSongChanged(int songIndex);

        void onError(String error);
    }

    public static class PlaybackState {
        public boolean isPlaying;
        public long currentPosition;
        public long duration;
        public PlayMode playMode;

        public PlaybackState(boolean isPlaying, long currentPosition, long duration, PlayMode playMode) {
            this.isPlaying = isPlaying;
            this.currentPosition = currentPosition;
            this.duration = duration;
            this.playMode = playMode;
        }
    }

    private static final String TAG = "MusicPlaybackManager";

    private final Context context;
    private MusicService musicService;
    private PlaybackStateListener playbackStateListener;
    private PlaylistData currentPlaylist;
    private static final int PlaylistId = 3778678; // 热歌榜ID

    // 创建一个 PlayerClient 对象
    PlayerClient playerClient = null;

    public MusicPlaybackManager(Context context) {
        this.context = context;
        bindService();
    }

    private void bindService() {

        musicService = new MusicService();
        musicService.initializeCacheManager(this.context);

        if (playerClient == null) {
            playerClient = PlayerClient.newInstance(context, MyPlayerService.class);
        }

        // 连接到 PlayerService
        playerClient.connect(success -> {
            Log.d(TAG, "连接 connect: " + success);
        });

        // 创建播放列表
        createPlaylist();


    }

    private void createPlaylist() {
        if (musicService == null) return;
        musicService.cancelCurrentRequest();
        musicService.getPlaylistAsync(PlaylistId)
                .thenAccept(playlist -> {
                    currentPlaylist = playlist;
                    if (playlist != null && !playlist.songs.isEmpty()) {
                        var playlistBuilder = new Playlist.Builder();
                        for (int i = 0; i < playlist.songs.size(); i++) {
                            var song = playlist.songs.get(i);
                            var musicItem = createMusicItem(song);
                            playlistBuilder.append(musicItem);
                            Log.d(TAG, "添加歌曲: " + song.getName());
                        }
                        var list = playlistBuilder.build();
                        // 设置播放列表，并播放音乐
                        playerClient.setPlaylist(list, false);
                    }

                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "加载歌单失败", throwable);
                    if (playbackStateListener != null) {
                        playbackStateListener.onError("加载歌单失败: " + throwable.getMessage());
                    }
                    return null;
                });

    }

    private MusicItem createMusicItem(PlaylistData.Song song) {
        return new MusicItem.Builder()
                .setTitle(song.getAl_name())
                .setArtist(song.getAr_name())
                .setAlbum(song.getName())
                .setDuration(-1)
                .setUri(song.getUrl())
                .setIconUri(song.getPic())
                .build();
    }


    public void setPlaybackStateListener(PlaybackStateListener listener) {
        this.playbackStateListener = listener;
    }

    public void loadPlaylist(int playlistId, boolean forceRefresh) {
        if (musicService == null) return;
        musicService.cancelCurrentRequest();
        musicService.getPlaylistAsync(playlistId)
                .thenAccept(playlist -> {
                    currentPlaylist = playlist;
                    if (playbackStateListener != null) {
                        playbackStateListener.onPlaylistUpdated(playlist);
                    }
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "加载歌单失败", throwable);
                    if (playbackStateListener != null) {
                        playbackStateListener.onError("加载歌单失败: " + throwable.getMessage());
                    }
                    return null;
                });
    }

    public void playCurrentIndexSong(int songIndex) {
        if (currentPlaylist == null || currentPlaylist.songs.isEmpty()) {
            Log.e(TAG, "播放列表为空");
            return;
        }

        PlaylistData.Song song = currentPlaylist.songs.get(songIndex);
        if (song.getUrl()!=null && !song.getUrl().isEmpty()){
            int position = playerClient.getPlayPosition();
            playerClient.skipToPosition(position);
            return;
        }

        musicService.getSongUrl(song.id)
                .thenAccept(songData -> {
                    if (songData != null && (songData.status == 200 ||
                            (songData.getUrl() != null && !songData.getUrl().isEmpty()))) {
                        // 成功获取歌曲链接
                        song.setUrl(songData.getUrl());
                        playerClient.getPlaylist(playlist -> {
                            int position = playerClient.getPlayPosition();
                            playlist.get( position).setUri(song.getUrl());
//                            playlist.get( position).setIconUri(song.getUrl());
                            playerClient.skipToPosition(position);
                        });
                        // 播放歌曲时启动进度更新


                    } else {
                        Log.e(TAG, "无法获取歌曲播放链接: " + (songData != null ? songData.getMsg() : "未知错误"));
                        if (playbackStateListener != null) {
                            playbackStateListener.onError("无法获取歌曲播放链接");
                        }
                        handleSongPlaybackFailure();
                    }
                })
                .exceptionally(throwable -> {
                    Log.e(TAG, "获取歌曲链接失败", throwable);
                    if (playbackStateListener != null) {
                        playbackStateListener.onError("获取歌曲链接失败: " + throwable.getMessage());
                    }
                    handleSongPlaybackFailure();
                    return null;
                });
    }

    public void togglePlayPause() {

    }

    public void playNextSong() {

    }

    public void playPreviousSong() {


    }

    public boolean isPlaying() {
        return false;
    }

    public void setPlayMode(PlayMode playMode) {

    }

    public void seekTo(int position) {

    }

    public void updatePlaybackState() {

        Log.d(TAG, "updatePlaybackState 更新播放完成");
    }


    public PlaylistData getCurrentPlaylist() {
        return currentPlaylist;
    }

    public int getCurrentSongIndex() {
        return -1;
    }

    private void handleSongPlaybackFailure() {

    }


    public void destroy() {


    }
}
