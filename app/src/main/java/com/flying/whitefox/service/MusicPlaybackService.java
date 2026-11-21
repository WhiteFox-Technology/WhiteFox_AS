package com.flying.whitefox.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionToken;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.flying.whitefox.MainActivity;
import com.flying.whitefox.R;
import com.flying.whitefox.data.model.music.PlayMode;
import com.flying.whitefox.data.model.music.PlaylistData;
import com.flying.whitefox.data.model.music.SongData;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


// 注意：Media3 推荐继承 MediaSessionService（而非普通 Service），自动整合会话
public class MusicPlaybackService extends MediaSessionService {
    private static final String TAG = "MusicPlaybackService";

    private static final String SP_NAME = "Media3MusicState";
    // 核心组件
    private ExoPlayer mExoPlayer;
    private MediaSession mMediaSession;
    private final IBinder mBinder = new MusicBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper()); // 添加主线程Handler
    // 播放状态
    private PlaylistData.Song mCurrentSong;
    private PlayMode mPlayMode = PlayMode.SEQUENTIAL;
    private final Random mRandom = new Random();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(); // 异步加载封面
    // 回调接口
    private OnPlaybackListener mPlaybackListener;
    // 默认专辑封面
    private Bitmap mDefaultAlbumArt;

    // 进度更新相关
    private static final int PROGRESS_UPDATE_INTERVAL = 1000; // 1秒更新一次
    private boolean isProgressUpdating = false;

    private  MusicNotificationMgr musicNotificationMgr;

    // 设置播放回调
    public void setPlaybackListener(OnPlaybackListener listener) {
        mPlaybackListener = listener;
    }

    // 播放回调接口
    public interface OnPlaybackListener {
        void onPlaybackCompleted(); // 播放完成（下一首）

        void onPlayModeChanged(PlayMode mode); // 播放模式变化

        void onError(String errorMsg); // 播放错误

        void onProgressUpdate(long currentPosition, long duration); // 播放进度更新
    }

    // Binder 类（供 Activity 绑定）
    public class MusicBinder extends Binder {
        public MusicPlaybackService getService() {
            return MusicPlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "服务创建");
        // 初始化默认封面
        mDefaultAlbumArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_default_album_art);
        // 1. 初始化 ExoPlayer（播放核心）
        initExoPlayer();
        // 2. 初始化 MediaSession（系统集成）
        initMediaSession();
        // 3. 初始化自动通知（Media3 内置，无需手动构建）
        initMediaNotification();
        // 4. 恢复上次播放状态
        restorePlaybackState();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        // This example always accepts the connection request
        return mMediaSession;
    }

    // -------------------------- 1. 初始化 ExoPlayer（播放核心） --------------------------
    private void initExoPlayer() {

        // 创建 ExoPlayer 实例（Media3 简化创建逻辑）
        mExoPlayer = new ExoPlayer.Builder(this)
                .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true) // 音频属性（焦点管理自动处理）
                .setHandleAudioBecomingNoisy(true) // 耳机拔插时暂停
                .build();

        // 监听播放状态变化
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                updatePlaybackState(); // 同步播放状态到锁屏
                // 播放完成（状态为 STATE_ENDED）
                if (playbackState == Player.STATE_ENDED) {
                    handlePlaybackCompleted();
                }
            }

            // 2. 进度变化（实时同步锁屏进度条）
            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPos, @NonNull Player.PositionInfo newPos, int reason) {
                Player.Listener.super.onPositionDiscontinuity(oldPos, newPos, reason);
                updatePlaybackState(); // 进度变化时更新状态
                // 回调进度到外部（如 Activity）
                if (mPlaybackListener != null) {
                    mainHandler.post(() -> {
                        mPlaybackListener.onProgressUpdate(
                                mExoPlayer.getCurrentPosition(),
                                mExoPlayer.getDuration()
                        );
                    });
                }
            }

            // 3. 媒体项切换（如切歌后同步元数据）
            @Override
            public void onMediaItemTransition(@Nullable androidx.media3.common.MediaItem mediaItem, int reason) {
                Player.Listener.super.onMediaItemTransition(mediaItem, reason);
                if (mCurrentSong != null) {
                    updateMediaSessionMetadata(); // 切歌后更新元数据
                }
            }

            // 4. 修复 onPlayerError 方法签名（添加 @Override，确保被调用）
            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                Player.Listener.super.onPlayerError(error);
                String errorMsg = "播放错误：" + error.getMessage();
                Log.e(TAG, errorMsg, error);
                if (mPlaybackListener != null) {
                    mainHandler.post(() -> mPlaybackListener.onError(errorMsg));
                }
            }
        });
    }


    // -------------------------- 2. 初始化 MediaSession（系统集成） --------------------------
    private void initMediaSession() {
        // 创建 MediaSession（关联 ExoPlayer，自动处理会话回调）
        MediaSession.Callback callback = new MediaSession.Callback() {
            public void onPlay() {
                resume();
            }

            public void onPause() {
                pause();
            }

            public void onSkipToNext() {
                // 通知外部切换到下一首歌曲
                if (mPlaybackListener != null) {
                    mainHandler.post(() -> mPlaybackListener.onPlaybackCompleted());
                }
            }

            public void onSkipToPrevious() {
                // 对于上一首，我们可以简单地重新播放当前歌曲或者跳转到0位置
                mainHandler.post(() -> {
                    if (mExoPlayer != null) {
                        mExoPlayer.seekTo(0);
                    }
                });
            }

            public void onSetRepeatMode(@NonNull MediaSession session, int repeatMode) {
                // 同步 Media3 重复模式与自定义 PlayMode
                switch (repeatMode) {
                    case Player.REPEAT_MODE_ONE:
                        setPlayMode(PlayMode.SINGLE_LOOP);
                        break;
                    case Player.REPEAT_MODE_ALL:
                        setPlayMode(PlayMode.SEQUENTIAL);
                        break;
                    case Player.REPEAT_MODE_OFF:
                        setPlayMode(PlayMode.SEQUENTIAL);
                        break;
                }
            }

            // 移除 @Override 注解，因为该方法可能不是直接覆盖父类方法
            public void onSetShuffleModeEnabled(@NonNull MediaSession session, boolean enabled) {
                // 同步 Media3 随机模式与自定义 PlayMode
                setPlayMode(enabled ? PlayMode.RANDOM : PlayMode.SEQUENTIAL);
            }
        };

        // 创建 PendingIntent 用于点击通知时打开应用
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        mMediaSession = new MediaSession.Builder(this, mExoPlayer)
                .setSessionActivity(pendingIntent) // 点击通知打开应用
                .setCallback(callback) // 在 Builder 中设置回调
                .build();
    }

    // -------------------------- 3. 初始化自动通知（Media3 内置） --------------------------
    private void initMediaNotification() {
        musicNotificationMgr = new MusicNotificationMgr();
        musicNotificationMgr.initMediaNotification(this,mExoPlayer,mMediaSession,mCurrentSong,mDefaultAlbumArt);
    }


    // -------------------------- 4. 播放控制核心方法 --------------------------
    // 播放指定歌曲
    @OptIn(markerClass = UnstableApi.class)
    public void playSong(SongData song) {
        if (song == null || song.getSecureUrl() == null) {
            String error = "歌曲URL无效";
            Log.w(TAG, error);
            if (mPlaybackListener != null) {
                mainHandler.post(() -> mPlaybackListener.onError(error));
            }
            return;
        }

        // 确保在主线程中执行Player操作
        mainHandler.post(() -> {
            // 保存当前歌曲信息
            mCurrentSong = convertSongData(song);
            // 构建 MediaItem（Media3 统一媒体资源模型）
            MediaItem mediaItem = MediaItem.fromUri(song.getSecureUrl());
            // 设置媒体资源并准备播放
            mExoPlayer.setMediaItem(mediaItem);
            mExoPlayer.prepare();
            mExoPlayer.play(); // 自动播放

            // 更新媒体会话的元数据
            updateMediaSessionMetadata();

            // 加载专辑封面（更新通知大图标）
            loadAlbumArt(song.getPic());
            // 保存播放状态
            savePlaybackState();
            // 启动进度更新
            startProgressUpdate();
            Log.d(TAG, "开始播放：" + song.getName());
        });
    }

//    // 更新媒体会话元数据
//    @UnstableApi
//    private void updateMediaSessionMetadata() {
//        if (mMediaSession != null && mCurrentSong != null) {
//            androidx.media3.common.MediaMetadata metadata = new androidx.media3.common.MediaMetadata.Builder()
//                    .setTitle(mCurrentSong.getName())
//                    .setArtist(mCurrentSong.getAr_name())
//                    .setAlbumTitle(mCurrentSong.getAl_name())
//                    .build();
//            mMediaSession.setSessionExtras(metadata.toBundle());
//        }
//    }

    // 新增：保存当前封面的成员变量（替代原方法中临时变量）
    private Bitmap mCurrentAlbumArt;

    @OptIn(markerClass = UnstableApi.class)
    private void updateMediaSessionMetadata() {
        if (mMediaSession == null || mCurrentSong == null || mExoPlayer == null) return;

        // 1. 构建 MediaMetadata（Media3 标准元数据模型，锁屏会自动解析）
        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setTitle(mCurrentSong.getName() != null ? mCurrentSong.getName() : "未知歌曲")
                .setArtist(mCurrentSong.getAr_name() != null ? mCurrentSong.getAr_name() : "未知艺术家")
                .setAlbumTitle(mCurrentSong.getAl_name() != null ? mCurrentSong.getAl_name() : "未知专辑")
                .setDurationMs(mExoPlayer.getDuration() < 0 ? 0 : mExoPlayer.getDuration()); // 歌曲总时长（锁屏进度条用）

        // 2. 处理专辑封面（Bitmap 转字节数组，Media3 要求格式）
        if (mCurrentAlbumArt != null) {
            byte[] artworkBytes = bitmapToByteArray(mCurrentAlbumArt);
            if (artworkBytes != null) {
                metadataBuilder.setArtworkData(
                        artworkBytes,
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER // 封面类型（锁屏会显示）
                );
            }
        } else {
            // 用默认封面
            byte[] defaultArtBytes = bitmapToByteArray(mDefaultAlbumArt);
            if (defaultArtBytes != null) {
                metadataBuilder.setArtworkData(
                        defaultArtBytes,
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER
                );
            }
        }

        // 3. 主线程更新元数据（避免跨线程异常）
        mainHandler.post(() -> {
            if (mMediaSession != null) {
//                mMediaSession.setMetadata(metadataBuilder.build());
                mMediaSession.setSessionExtras(metadataBuilder.build().toBundle());
            }
        });
    }


    //    「播放状态同步」方法（锁屏控制按钮 / 进度）
// 新增：同步 PlaybackState（锁屏控制按钮、进度条核心）
    @OptIn(markerClass = UnstableApi.class)
    private void updatePlaybackState() {
        if (mMediaSession == null || mExoPlayer == null) return;

        // 1. 确定当前播放状态（Media3 标准状态）
        int state;
        if (mExoPlayer.isPlaying()) {
            state = PlaybackState.STATE_PLAYING;
        } else if (mExoPlayer.getPlaybackState() == Player.STATE_READY) {
            state = PlaybackState.STATE_PAUSED;
        } else if (mExoPlayer.getPlaybackState() == Player.STATE_BUFFERING) {
            state = PlaybackState.STATE_BUFFERING;
        } else {
            state = PlaybackState.STATE_STOPPED;
        }

        // 2. 构建 PlaybackState（声明支持的控制动作：播放/暂停、切歌、进度跳转）
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setState(
                        state,
                        mExoPlayer.getCurrentPosition(), // 当前进度（锁屏进度条用）
                        1f   // 播放速度（默认 1.0f）
                )
                .setActions(
                        PlaybackState.ACTION_PLAY |        // 播放
                                PlaybackState.ACTION_PAUSE |       // 暂停
                                PlaybackState.ACTION_SKIP_TO_NEXT |// 下一曲
                                PlaybackState.ACTION_SKIP_TO_PREVIOUS |// 上一曲
                                PlaybackState.ACTION_SEEK_TO |     // 进度跳转
                                PlaybackState.ACTION_STOP          // 停止
                );

        // 3. 同步重复/随机模式（锁屏可能显示模式图标）
//        // 不再使用BundleableUtil，而是通过MediaSession的其他方法设置重复和随机模式
//        switch (mExoPlayer.getRepeatMode()) {
//            case Player.REPEAT_MODE_ONE:
//                // 我们已经在ExoPlayer中设置了repeatMode，MediaSession会自动同步
//                break;
//            case Player.REPEAT_MODE_ALL:
//                // 我们已经在ExoPlayer中设置了repeatMode，MediaSession会自动同步
//                break;
//            default:
//                // 我们已经在ExoPlayer中设置了repeatMode，MediaSession会自动同步
//                break;
//        }

        // 4. 主线程更新状态
//        mainHandler.post(() -> {
//            if (mMediaSession != null) {
////                mMediaSession.setPlaybackState(stateBuilder.build());
//                mMediaSession.setPlaybackState(stateBuilder.build());
//            }
//        });

    }

    // 处理播放完成（根据播放模式切歌）
    private void handlePlaybackCompleted() {
        Log.d(TAG, "播放完成，处理下一首");
        stopProgressUpdate();
        mainHandler.post(() -> {
            switch (mPlayMode) {
                case SINGLE_LOOP:
                    // 单曲循环：重新播放
                    mExoPlayer.seekTo(0);
                    mExoPlayer.play();
                    startProgressUpdate();
                    break;
                case RANDOM:
                case SEQUENTIAL:
                    // 随机/顺序：通知外部切下一首
                    if (mPlaybackListener != null) {
                        mPlaybackListener.onPlaybackCompleted();
                    }
                    break;
            }
            savePlaybackState();
        });
    }

    // 设置播放模式（同步 ExoPlayer 的重复/随机模式）
    public void setPlayMode(PlayMode mode) {
        if (mode == null || mode == mPlayMode) return;

        mainHandler.post(() -> {
            mPlayMode = mode;

            // 同步 ExoPlayer 的重复/随机模式（Media3 自动处理系统集成）
            switch (mode) {
                case SINGLE_LOOP:
                    mExoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
                    mExoPlayer.setShuffleModeEnabled(false);
                    break;
                case RANDOM:
                    mExoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
                    mExoPlayer.setShuffleModeEnabled(true);
                    break;
                case SEQUENTIAL:
                    mExoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
                    mExoPlayer.setShuffleModeEnabled(false);
                    break;
            }

            // 通知外部模式变化
            if (mPlaybackListener != null) {
                mPlaybackListener.onPlayModeChanged(mode);
            }
            savePlaybackState();
            Log.d(TAG, "播放模式切换为：" + mode.getDescription());
        });
    }

    // 加载专辑封面（更新通知大图标）
    @UnstableApi
    private void loadAlbumArt(String picUrl) {
        // 如果图片URL没有变化，避免重复加载
        if (picUrl == null || picUrl.isEmpty()) {
            updateNotificationAlbumArt(mDefaultAlbumArt);
            return;
        }

        // 异步加载封面（避免阻塞主线程）
        mExecutor.execute(() -> {
            try {
                Glide.with(this)
                        .asBitmap()
                        .load(picUrl)
                        .override(200, 200) // 固定尺寸，避免OOM
                        .placeholder(R.drawable.ic_default_album_art) // 占位图
                        .error(R.drawable.ic_default_album_art)       // 错误图
                        .listener(new RequestListener<Bitmap>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                                Log.e(TAG, "封面加载失败", e);
                                updateNotificationAlbumArt(mDefaultAlbumArt);
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                                updateNotificationAlbumArt(resource);
                                return false;
                            }
                        })
                        .submit();
            } catch (Exception e) {
                Log.e(TAG, "加载封面时发生异常", e);
                updateNotificationAlbumArt(mDefaultAlbumArt);
            }
        });
    }

    // 更新通知的专辑封面（Media3 提供方法）
    @UnstableApi
    private void updateNotificationAlbumArt(Bitmap bitmap) {
        // MediaSessionService会自动处理通知更新
        // 通过MediaSession的元数据来更新通知内容
        if (mMediaSession != null && mCurrentSong != null) {
            // 创建媒体元数据
            androidx.media3.common.MediaMetadata.Builder metadataBuilder = new androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(mCurrentSong.getName())
                    .setArtist(mCurrentSong.getAr_name())
                    .setAlbumTitle(mCurrentSong.getAl_name());

            // 如果有专辑封面，则设置
            if (bitmap != null) {
                metadataBuilder.setArtworkData(
                        bitmapToByteArray(bitmap),
                        androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER
                );
            }

            // 更新媒体会话的元数据
            mMediaSession.setSessionExtras(metadataBuilder.build().toBundle());
        }
    }

    // 将Bitmap转换为字节数组的辅助方法
    private byte[] bitmapToByteArray(Bitmap bitmap) {
        if (bitmap == null) return null;

        java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return stream.toByteArray();
    }

    // -------------------------- 5. 状态持久化 --------------------------
    private void savePlaybackState() {
        if (mCurrentSong == null || mExoPlayer == null) return;
        SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();

        // 保存歌曲信息
        editor.putString("song_url", mCurrentSong.getUrl());
        editor.putString("song_name", mCurrentSong.getName());
        editor.putString("song_artist", mCurrentSong.getAr_name());
        editor.putString("song_pic", mCurrentSong.getPic());
        // 保存播放状态
        editor.putLong("current_position", mExoPlayer.getCurrentPosition());
        editor.putString("play_mode", mPlayMode.name());
        editor.putBoolean("is_playing", mExoPlayer.isPlaying());
        editor.apply();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void restorePlaybackState() {
        SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
        String songUrl = sp.getString("song_url", null);
        if (songUrl == null) return;

        // 恢复歌曲信息
        mCurrentSong = new PlaylistData.Song();
        mCurrentSong.setUrl(songUrl); // 使用setUrl()而不是setSecureUrl()
        mCurrentSong.setName(sp.getString("song_name", "未知歌曲"));
        mCurrentSong.setAr_name(sp.getString("song_artist", "未知歌手"));
        mCurrentSong.setPic(sp.getString("song_pic", null));

        // 恢复播放状态
        long lastPosition = sp.getLong("current_position", 0);
        boolean isPlaying = sp.getBoolean("is_playing", false);
        String modeStr = sp.getString("play_mode", PlayMode.SEQUENTIAL.name());
        setPlayMode(PlayMode.valueOf(modeStr));

        // 恢复播放
        MediaItem mediaItem = MediaItem.fromUri(songUrl);
        mExoPlayer.setMediaItem(mediaItem);
        mExoPlayer.prepare();
        mExoPlayer.seekTo(lastPosition);
        if (isPlaying) {
            mExoPlayer.play();
        }

        // 恢复封面
        loadAlbumArt(mCurrentSong.getPic());
        Log.d(TAG, "恢复上次播放：" + mCurrentSong.getName());
    }

    // -------------------------- 6. 工具方法与 Getter --------------------------
    // SongData 转 PlaylistData.Song
    private PlaylistData.Song convertSongData(SongData songData) {
        PlaylistData.Song song = new PlaylistData.Song();
        song.setName(songData.getName());
        song.setAr_name(songData.getAr_name());
        song.setPic(songData.getPic());
        song.setAl_name(songData.getAl_name()); // 使用setAl_name()而不是setAlbum()
        song.setUrl(songData.getSecureUrl());
        return song;
    }

    // 获取当前播放歌曲
    public PlaylistData.Song getCurrentSong() {
        return mCurrentSong;
    }

    private boolean isDestroyed = false; // 添加销毁状态标志

    // 是否正在播放
    public boolean isPlaying() {
        if (isDestroyed || mExoPlayer == null) return false;
        // 使用Handler在主线程中获取播放状态
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return mExoPlayer.isPlaying();
        } else {
            // 如果在非主线程中调用，返回最近的状态（需要添加一个字段来跟踪）
            // 这里简化处理，你可以根据需要添加更复杂的逻辑
            try {
                // 使用同步方式在主线程中获取状态
                final boolean[] result = new boolean[1];
                final Exception[] exception = new Exception[1];

                CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        result[0] = mExoPlayer.isPlaying();
                    } catch (Exception e) {
                        exception[0] = e;
                    } finally {
                        latch.countDown();
                    }
                });

                latch.await(1, TimeUnit.SECONDS); // 等待最多1秒

                if (exception[0] != null) {
                    throw exception[0];
                }

                return result[0];
            } catch (Exception e) {
                Log.e(TAG, "获取播放状态时发生错误", e);
                return false;
            }
        }
    }

    // 当前播放进度（毫秒）
    public long getCurrentPosition() {
        if (isDestroyed || mExoPlayer == null) return 0;
        // 使用Handler在主线程中获取播放进度
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return mExoPlayer.getCurrentPosition();
        } else {
            try {
                final long[] result = new long[1];
                final Exception[] exception = new Exception[1];

                CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        result[0] = mExoPlayer.getCurrentPosition();
                    } catch (Exception e) {
                        exception[0] = e;
                    } finally {
                        latch.countDown();
                    }
                });

                latch.await(1, TimeUnit.SECONDS);

                if (exception[0] != null) {
                    throw exception[0];
                }

                return result[0];
            } catch (Exception e) {
                Log.e(TAG, "获取播放进度时发生错误", e);
                return 0;
            }
        }
    }

    // 歌曲总时长（毫秒）
    public long getDuration() {
        if (isDestroyed || mExoPlayer == null || mExoPlayer.getMediaItemCount() <= 0) return 0;
        // 使用Handler在主线程中获取歌曲时长
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return mExoPlayer.getDuration();
        } else {
            try {
                final long[] result = new long[1];
                final Exception[] exception = new Exception[1];

                CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        result[0] = mExoPlayer.getDuration();
                    } catch (Exception e) {
                        exception[0] = e;
                    } finally {
                        latch.countDown();
                    }
                });

                latch.await(1, TimeUnit.SECONDS);

                if (exception[0] != null) {
                    throw exception[0];
                }

                return result[0];
            } catch (Exception e) {
                Log.e(TAG, "获取歌曲时长时发生错误", e);
                return 0;
            }
        }
    }

    // 获取播放模式
    public PlayMode getPlayMode() {
        if (isDestroyed) return PlayMode.SEQUENTIAL;
        return mPlayMode;
    }

    // 暂停播放
    public void pause() {
        if (isDestroyed || mExoPlayer == null) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            mExoPlayer.pause();
            stopProgressUpdate();
        } else {
            mainHandler.post(() -> {
                if (mExoPlayer != null && !isDestroyed) {
                    mExoPlayer.pause();
                    stopProgressUpdate();
                }
            });
        }
    }

    // 恢复播放
    public void resume() {
        if (isDestroyed || mExoPlayer == null) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            mExoPlayer.play();
            startProgressUpdate();
        } else {
            mainHandler.post(() -> {
                if (mExoPlayer != null && !isDestroyed) {
                    mExoPlayer.play();
                    startProgressUpdate();
                }
            });
        }
    }

    // 跳转到指定位置
    public void seekTo(int position) {
        if (isDestroyed || mExoPlayer == null) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            mExoPlayer.seekTo(position);
        } else {
            mainHandler.post(() -> {
                if (mExoPlayer != null && !isDestroyed) {
                    mExoPlayer.seekTo(position);
                }
            });
        }
    }

    // 获取下一首歌曲索引
    public int getNextSongIndex(int currentIndex, int totalCount) {
        if (totalCount <= 1) return 0;
        switch (mPlayMode) {
            case RANDOM:
                int randomIdx = mRandom.nextInt(totalCount);
                return randomIdx == currentIndex ? (randomIdx + 1) % totalCount : randomIdx;
            case SINGLE_LOOP:
                return currentIndex;
            default:
                return (currentIndex + 1) % totalCount;
        }
    }

    // 获取上一首歌曲索引
    public int getPreviousSongIndex(int currentIndex, int totalCount) {
        if (totalCount <= 1) return 0;
        switch (mPlayMode) {
            case RANDOM:
                int randomIdx = mRandom.nextInt(totalCount);
                return randomIdx == currentIndex ? (randomIdx - 1 + totalCount) % totalCount : randomIdx;
            case SINGLE_LOOP:
                return currentIndex;
            default:
                return (currentIndex - 1 + totalCount) % totalCount;
        }
    }

    // -------------------------- 7. 服务生命周期 --------------------------
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent); // 必须调用父类方法（MediaSessionService 要求）
        return mBinder; // 供 Activity 绑定
    }

    @Override
    public void onDestroy() {
        stopProgressUpdate(); // 停止进度更新
        isDestroyed = true; // 标记为已销毁

        // 1. 停用并释放 MediaSession
        if (mMediaSession != null) {
            mMediaSession.release();
            mMediaSession = null;
        }

        // 2. 释放 ExoPlayer
        if (mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer = null;
        }

        // 3. 关闭线程池（中断残留任务）
        if (mExecutor != null && !mExecutor.isShutdown()) {
            mExecutor.shutdownNow();
        }
        stopForeground(true);

        super.onDestroy();
        Log.d(TAG, "服务销毁");
    }

    // 进度更新任务
    private final Runnable progressUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayer != null && mExoPlayer.isPlaying() && mPlaybackListener != null) {
                // 通知播放进度更新
                long currentPosition = mExoPlayer.getCurrentPosition();
                long duration = mExoPlayer.getDuration();
                mainHandler.post(() -> {
                    if (mPlaybackListener != null) {
                        mPlaybackListener.onProgressUpdate(currentPosition, duration);
                    }
                });
            }

            // 继续定期更新
            if (isProgressUpdating) {
                mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
            }
        }
    };

    // 启动进度更新
    private void startProgressUpdate() {
        if (!isProgressUpdating) {
            isProgressUpdating = true;
            mainHandler.removeCallbacks(progressUpdateRunnable);
            mainHandler.post(progressUpdateRunnable);
        }
    }

    // 停止进度更新
    private void stopProgressUpdate() {
        isProgressUpdating = false;
        mainHandler.removeCallbacks(progressUpdateRunnable);
    }
}