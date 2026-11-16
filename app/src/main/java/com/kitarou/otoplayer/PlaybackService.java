package com.kitarou.otoplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** 画面を消しても再生を続けるためのサービス（通知とBluetoothのボタンにも対応） */
public class PlaybackService extends Service implements Player.Listener {

    static final String CH = "play";
    static final int ID = 1;
    static final String A_TOGGLE = "toggle", A_NEXT = "next", A_PREV = "prev", A_CLOSE = "close";

    static boolean running = false;

    public static void ensure(Context c) {
        if (running) return;
        Intent i = new Intent(c, PlaybackService.class);
        try {
            c.startForegroundService(i);
        } catch (Exception ignored) { }
    }

    Player player;
    MediaSession session;
    PowerManager.WakeLock wake;
    String lastKey = "";

    final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { player.pause(); }  // スピーカーが切れたら一時停止
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        player = Player.get(this);

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "再生中", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "oto");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (!player.isPlaying()) player.toggle(); }
            @Override public void onPause() { player.pause(); }
            @Override public void onSkipToNext() { player.next(); }
            @Override public void onSkipToPrevious() { player.prev(); }
            @Override public void onStop() { player.pause(); }
        });
        session.setActive(true);

        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oto:play");
        wake.setReferenceCounted(false);

        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        player.addListener(this);

        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (A_TOGGLE.equals(a)) player.toggle();
        else if (A_NEXT.equals(a)) player.next();
        else if (A_PREV.equals(a)) player.prev();
        else if (A_CLOSE.equals(a)) {
            player.pause();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onPlayerChanged() {
        boolean playing = player.isPlaying();
        MediaItem it = player.current();
        String key = (it != null ? it.file.getPath() : "") + playing;
        if (playing) wake.acquire(); else if (wake.isHeld()) wake.release();
        if (key.equals(lastKey)) return;
        lastKey = key;

        PlaybackState st = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        Math.max(0, player.time()), 1f)
                .build();
        session.setPlaybackState(st);
        if (it != null) {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, it.folder)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, player.length()))
                    .build());
        }
        getSystemService(NotificationManager.class).notify(ID, build());
    }

    PendingIntent act(String a) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, a.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    Notification build() {
        MediaItem it = player.current();
        boolean playing = player.isPlaying();
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(it != null ? it.title : "おとプレイヤー")
                .setContentText(it != null ? it.folder : "")
                .setContentIntent(open)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "前", act(A_PREV)).build())
                .addAction(new Notification.Action.Builder(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "一時停止" : "再生", act(A_TOGGLE)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "次", act(A_NEXT)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "閉じる", act(A_CLOSE)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        player.removeListener(this);
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        if (wake.isHeld()) wake.release();
        session.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
