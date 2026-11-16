package com.kitarou.otoplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 再生の中心（アプリ全体で1つ） */
public class Player {

    public interface Listener { void onPlayerChanged(); }

    public static final int BANDS = 10;
    /** 重低音をどの帯域にどれだけ足すか（VLCの10バンド：60,170,310,600Hz…） */
    static final float[] BASS_WEIGHT = {1.0f, 0.65f, 0.25f, 0, 0, 0, 0, 0, 0, 0};

    private static Player inst;

    public static synchronized Player get(Context c) {
        if (inst == null) inst = new Player(c.getApplicationContext());
        return inst;
    }

    final Context ctx;
    final LibVLC vlc;
    final MediaPlayer mp;
    final Handler main = new Handler(Looper.getMainLooper());
    final List<Listener> listeners = new CopyOnWriteArrayList<>();

    List<MediaItem> queue = new ArrayList<>();
    int index = -1;
    boolean loaded = false;
    int errCount = 0;
    /** 再生できなかった時のお知らせ（画面に出す） */
    public String lastError = null;
    AudioFocusRequest focusReq;

    // 音質設定
    public final float[] bands = new float[BANDS];
    public float bass = 0;
    public boolean protect = true;

    private Player(Context c) {
        ctx = c;
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--audio-resampler=soxr");   // 高品質なサンプルレート変換
        opts.add("--no-audio-time-stretch");
        vlc = new LibVLC(c, opts);
        mp = new MediaPlayer(vlc);
        mp.setEventListener(ev -> {
            if (ev.type == MediaPlayer.Event.EndReached) {
                main.post(this::onEnd);
            } else if (ev.type == MediaPlayer.Event.EncounteredError) {
                main.post(this::onError);
            } else if (ev.type == MediaPlayer.Event.Playing) {
                errCount = 0;
                lastError = null;
            }
            fire();
        });
        loadEq();
        applyEq();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    void fire() {
        main.post(() -> { for (Listener l : listeners) l.onPlayerChanged(); });
    }

    public MediaItem current() {
        return (index >= 0 && index < queue.size()) ? queue.get(index) : null;
    }

    public boolean isPlaying() { return mp.isPlaying(); }
    public long time() { return mp.getTime(); }
    public long length() { return mp.getLength(); }
    public void seek(long t) { if (loaded) mp.setTime(t); }

    public void play(List<MediaItem> list, int i) {
        queue = new ArrayList<>(list);
        index = i;
        start();
    }

    void start() {
        MediaItem it = current();
        if (it == null) return;
        Media m = new Media(vlc, it.file.getAbsolutePath());
        if (!it.video) m.addOption(":no-video");
        mp.setMedia(m);
        m.release();
        applyEq();
        requestFocus();
        mp.play();
        loaded = true;
        PlaybackService.ensure(ctx);
        fire();
    }

    public void toggle() {
        if (mp.isPlaying()) { mp.pause(); fire(); return; }
        if (current() == null) return;
        int st = mp.getPlayerState();
        boolean dead = st == Media.State.NothingSpecial || st == Media.State.Stopped
                || st == Media.State.Ended || st == Media.State.Error;
        if (!loaded || dead) {
            errCount = 0;
            start();               // 止まっている・終わっている時は最初から読み込み直す
        } else {
            requestFocus();
            mp.play();
        }
        fire();
    }

    private void onError() {
        MediaItem it = current();
        lastError = "再生できませんでした：" + (it != null ? it.file.getName() : "");
        loaded = false;
        errCount++;
        // 次の曲へ（ただし全部だめな時に回り続けないように）
        if (it != null && !it.video && errCount < Math.min(queue.size(), 5)) next();
        else fire();
    }

    void requestFocus() {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(f -> {
                            if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                                main.post(this::pause);   // 電話などが来たら一時停止
                        }, main)
                        .build();
            }
            am.requestAudioFocus(focusReq);
        } catch (Exception ignored) { }
    }

    public void pause() { if (mp.isPlaying()) mp.pause(); fire(); }

    public void next() {
        if (queue.isEmpty()) return;
        index = (index + 1) % queue.size();
        start();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (mp.getTime() > 3000) { mp.setTime(0); return; }
        index = (index - 1 + queue.size()) % queue.size();
        start();
    }

    public void stop() {
        mp.stop();
        loaded = false;
        fire();
    }

    private void onEnd() {
        MediaItem it = current();
        if (it != null && it.video) { stop(); return; }   // 動画は1本で止める
        next();
    }

    // ---------- イコライザー ----------

    void loadEq() {
        SharedPreferences p = ctx.getSharedPreferences("eq", 0);
        for (int i = 0; i < BANDS; i++) bands[i] = p.getFloat("b" + i, 0);
        bass = p.getFloat("bass", 0);
        protect = p.getBoolean("protect", true);
    }

    public void saveEq() {
        SharedPreferences.Editor e = ctx.getSharedPreferences("eq", 0).edit();
        for (int i = 0; i < BANDS; i++) e.putFloat("b" + i, bands[i]);
        e.putFloat("bass", bass);
        e.putBoolean("protect", protect);
        e.apply();
    }

    public void applyEq() {
        boolean flat = bass == 0;
        for (float b : bands) if (b != 0) flat = false;
        if (flat) {                  // 何もいじっていない時は無加工で通す
            mp.setEqualizer(null);
            return;
        }
        MediaPlayer.Equalizer eq = MediaPlayer.Equalizer.create();
        int n = Math.min(BANDS, MediaPlayer.Equalizer.getBandCount());
        float max = 0;
        for (int i = 0; i < n; i++) {
            float g = bands[i] + BASS_WEIGHT[i] * bass;
            g = Math.max(-20f, Math.min(20f, g));
            eq.setAmp(i, g);
            if (g > max) max = g;
        }
        eq.setPreAmp(protect ? Math.max(-20f, -max) : 0f);  // 音割れ防止
        mp.setEqualizer(eq);
    }
}
