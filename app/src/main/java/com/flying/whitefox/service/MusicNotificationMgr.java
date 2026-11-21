package com.flying.whitefox.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionToken;

import com.flying.whitefox.MainActivity;
import com.flying.whitefox.R;
import com.flying.whitefox.data.model.music.PlaylistData;
import com.flying.whitefox.ui.dashboard.DashboardFragment;

public class MusicNotificationMgr  {
    private static final String TAG = "MusicNotificationMgr";
    // 常量
    private static final int NOTIFICATION_ID = 1001;
    public void InitData()
    {
        
    }
    public void initMediaNotification(Context context, ExoPlayer mExoPlayer, MediaSession mMediaSession, PlaylistData.Song mCurrentSong, Bitmap mDefaultAlbumArt) {
        createNotificationChannel(context); // 先确保通知渠道已创建

        // 1. 构建基础通知（用系统 Notification.Builder，而非 NotificationCompat）
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+：指定通知渠道
            builder = new Notification.Builder(context, "media3_music_channel");
        } else {
            // Android 8.0 以下：直接构建
            builder = new Notification.Builder(context);
        }

        // 2. 设置通知基础信息（小图标、标题、艺术家）
        builder.setSmallIcon(R.drawable.ic_notifications_black_24dp) // 必需：状态栏小图标
                .setContentTitle(mCurrentSong != null ? mCurrentSong.getName() : "未知歌曲")
                .setContentText(mCurrentSong != null ? mCurrentSong.getAr_name() : "未知歌手")
                .setLargeIcon(mDefaultAlbumArt) // 锁屏显示的大图标（专辑封面）
                .setShowWhen(false) // 隐藏通知时间
                .setPriority(Notification.PRIORITY_LOW) // 媒体通知建议低优先级（不弹窗）
                .setVisibility(Notification.VISIBILITY_PUBLIC); // 锁屏可见

        // 3. 关键：添加原生 MediaStyle（系统 API，无需额外依赖）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 关联 MediaSession，让通知与播放状态同步
            Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2); // 紧凑视图显示前3个动作按钮（上一首、播放/暂停、下一首）
            builder.setStyle(mediaStyle);
        }

        // 4. 添加通知控制按钮（上一首、播放/暂停、下一首）
        // 上一首按钮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            builder.addAction(
                    new Notification.Action(
                            R.drawable.ic_previous, // 按钮图标
                            "上一首",
                            // 通过 MediaSession 获取按钮的 PendingIntent（自动关联播放逻辑）
                            mMediaSession.getSessionActivity()
                    )
            );
            // 播放/暂停按钮
            builder.addAction(
                    new Notification.Action(
                            mExoPlayer.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play,
                            mExoPlayer.isPlaying() ? "暂停" : "播放",
                            mMediaSession.getSessionActivity()
                    )
//                    MediaSession.getPlayPauseButtonPendingIntent(
//                            context,
//                            new ComponentName(context, MusicPlaybackService.class), // 同上
//                            mMediaSession.getToken()
//                    )
            );
            // 下一首按钮
            builder.addAction(
                    new Notification.Action(
                            R.drawable.ic_next,
                            "下一首",
                            mMediaSession.getSessionActivity()
                    )
            );
        }

        // 5. 设置通知点击事件（打开 MainActivity）
        Intent openAppIntent = new Intent(context, DashboardFragment.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openAppPi = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.setContentIntent(openAppPi);

        // 6. 构建通知
        Notification notification = builder.build();
        
        // 7. 如果context是Service实例，则启动前台服务
        if (context instanceof android.app.Service) {
            ((android.app.Service) context).startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel(Context context) {
        // 创建通知渠道 (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    "media3_music_channel",
                    "音乐播放服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于音乐播放的前台服务通知");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
