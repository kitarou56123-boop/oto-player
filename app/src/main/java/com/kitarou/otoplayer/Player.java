package com.kitarou.otoplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 再生の中心（アプリ全体で1つ）。
 * ふだんはVLCで再生し、VLCで鳴らない時はAndroid標準の仕組みに自動で切り替える。
 */
public class Player {

    public interface Listener { void onPlayerChanged(); }

    public static final int BANDS = 10;
    /** 重低音をどの帯域にどれだけ足すか（VLCの10バンド：60,170,310,600Hz…） */
    static final float[] BASS_WEIGHT = {1.0f, 0.65f, 0.25f, 0, 0, 0, 0, 0, 0, 0};
    static final float[] BAND_HZ = {60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000};

    private static Player inst;

    public static synchronized Player get(Context c) {
        if (inst == null) inst = new Player(c.getApplicationContext());
        return inst;
    }

    final Context ctx;
    LibVLC vlc;
    MediaPlayer mp;
    final Handler main = new Handler(Looper.getMainLooper());
    final List<Listener> listeners = new CopyOnWriteArrayList<>();

    List<MediaItem> queue = new ArrayList<>();
    int index = -1;
    boolean loaded = false;
    int errCount = 0;
    /** 標準の仕組みで鳴らせなかった曲だけ、VLCで再挑戦中 */
    boolean vlcFallback = false;
    /** 再生できなかった時のお知らせ（画面に出す） */
    public String lastError = null;
    AudioFocusRequest focusReq;

    // Android標準の再生（予備）
    android.media.MediaPlayer amp;
    Equalizer aeq;
    boolean useAndroid = false;

    // 音質設定
    public final float[] bands = new float[BANDS];
    public float bass = 0;
    public boolean protect = true;

    private Player(Context c) {
        ctx = c;
        try {
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--no-audio-time-stretch");
            vlc = new LibVLC(c, opts);
            mp = new MediaPlayer(vlc);
            mp.setEventListener(ev -> {
                if (useAndroid) return;
                if (ev.type == MediaPlayer.Event.EndReached) {
                    main.post(this::onEnd);
                } else if (ev.type == MediaPlayer.Event.EncounteredError) {
                    main.post(this::onVlcError);
                } else if (ev.type == MediaPlayer.Event.Playing) {
                    errCount = 0;
                    lastError = null;
                }
                fire();
            });
        } catch (Throwable t) {
            mp = null;   // VLCが使えない時は標準の仕組みだけで動かす
        }
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

    public boolean isPlaying() {
        try {
            if (useAndroid) return amp != null && amp.isPlaying();
            return mp != null && mp.isPlaying();
        } catch (Exception e) { return false; }
    }

    public long time() {
        try {
            if (useAndroid) return amp != null ? amp.getCurrentPosition() : 0;
            return mp != null ? mp.getTime() : 0;
        } catch (Exception e) { return 0; }
    }

    public long length() {
        try {
            if (useAndroid) return amp != null ? amp.getDuration() : 0;
            return mp != null ? mp.getLength() : 0;
        } catch (Exception e) { return 0; }
    }

    public void seek(long t) {
        if (!loaded) return;
        try {
            if (useAndroid) { if (amp != null) amp.seekTo((int) t); }
            else if (mp != null) mp.setTime(t);
        } catch (Exception ignored) { }
        fire();
    }

    /** 画面に出す「いまどうなっているか」 */
    public String stateText() {
        if (current() == null) return "";
        if (useAndroid) return "標準エンジンで再生";
        if (loaded && mp != null && !mp.isPlaying()) {
            int st = mp.getPlayerState();
            if (st == Media.State.Opening) return "読み込み中…";
        }
        return "";
    }

    public void play(List<MediaItem> list, int i) {
        queue = new ArrayList<>(list);
        index = i;
        errCount = 0;
        start();
    }

    /** VLCで鳴り始めなかったら標準の仕組みに切り替える見張り */
    final Runnable watchdog = () -> {
        MediaItem it = current();
        if (it == null || it.video || useAndroid || !loaded) return;
        if (mp == null || !mp.isPlaying()) startAndroid();
    };

    /** 標準の仕組みで再生中、時間表示を進める */
    final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!useAndroid) return;
            fire();
            main.postDelayed(this, 500);
        }
    };

    void start() {
        MediaItem it = current();
        if (it == null) return;
        releaseAndroid();
        main.removeCallbacks(watchdog);
        vlcFallback = false;
        if (!it.video) { startAndroid(); return; }   // 音楽は標準の仕組みですぐ再生
        if (mp == null) return;
        try {
            Media m = new Media(vlc, it.file.getAbsolutePath());
            if (!it.video) m.addOption(":no-video");
            mp.setMedia(m);
            m.release();
            applyEq();
            requestFocus();
            mp.play();
            loaded = true;
            PlaybackService.ensure(ctx);
            if (!it.video) main.postDelayed(watchdog, 3000);
        } catch (Throwable t) {
            if (!it.video) startAndroid();
        }
        fire();
    }

    /** 標準の仕組みで鳴らせない形式の曲だけ、VLCで鳴らす */
    boolean tryVlcMusic(MediaItem it) {
        if (mp == null || vlcFallback) return false;
        vlcFallback = true;
        releaseAndroid();
        try {
            Media m = new Media(vlc, it.file.getAbsolutePath());
            m.addOption(":no-video");
            mp.setMedia(m);
            m.release();
            applyEq();
            requestFocus();
            mp.play();
            loaded = true;
            lastError = null;
            PlaybackService.ensure(ctx);
            fire();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void startAndroid() {
        MediaItem it = current();
        if (it == null) return;
        main.removeCallbacks(watchdog);
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) { }
        releaseAndroid();
        try {
            amp = new android.media.MediaPlayer();
            amp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            amp.setDataSource(it.file.getAbsolutePath());
            amp.setOnCompletionListener(x -> main.post(this::onEnd));
            amp.setOnErrorListener((x, what, extra) -> {
                main.post(() -> {
                    if (tryVlcMusic(it)) return;
                    lastError = "再生できませんでした：" + it.file.getName();
                    releaseAndroid();
                    loaded = false;
                    errCount++;
                    if (errCount < Math.min(queue.size(), 5)) next(); else fire();
                });
                return true;
            });
            amp.prepare();
            useAndroid = true;
            loaded = true;
            applyEq();
            requestFocus();
            amp.start();
            errCount = 0;
            lastError = null;
            PlaybackService.ensure(ctx);
            main.removeCallbacks(ticker);
            main.post(ticker);
        } catch (Exception e) {
            if (tryVlcMusic(it)) return;
            releaseAndroid();
            loaded = false;
            lastError = "再生できませんでした：" + it.file.getName();
            errCount++;
            if (errCount < Math.min(queue.size(), 5)) { next(); return; }
        }
        fire();
    }

    void releaseAndroid() {
        main.removeCallbacks(ticker);
        if (aeq != null) { try { aeq.release(); } catch (Exception ignored) { } aeq = null; }
        if (amp != null) { try { amp.release(); } catch (Exception ignored) { } amp = null; }
        useAndroid = false;
    }

    public void toggle() {
        if (current() == null) return;
        if (isPlaying()) { pause(); return; }
        if (useAndroid && amp != null && loaded) {
            requestFocus();
            amp.start();
            main.removeCallbacks(ticker);
            main.post(ticker);
            fire();
            return;
        }
        boolean dead = true;
        if (mp != null && loaded) {
            int st = mp.getPlayerState();
            dead = st == Media.State.NothingSpecial || st == Media.State.Stopped
                    || st == Media.State.Ended || st == Media.State.Error;
        }
        if (dead) {
            errCount = 0;
            start();               // 止まっている・終わっている時は読み込み直す
        } else {
            requestFocus();
            mp.play();
        }
        fire();
    }

    public void pause() {
        main.removeCallbacks(watchdog);
        try {
            if (useAndroid) { if (amp != null && amp.isPlaying()) amp.pause(); }
            else if (mp != null && mp.isPlaying()) mp.pause();
        } catch (Exception ignored) { }
        fire();
    }

    public void next() {
        if (queue.isEmpty()) return;
        index = (index + 1) % queue.size();
        start();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (time() > 3000) { seek(0); return; }
        index = (index - 1 + queue.size()) % queue.size();
        start();
    }

    public void stop() {
        main.removeCallbacks(watchdog);
        releaseAndroid();
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) { }
        loaded = false;
        fire();
    }

    /** アプリを終了した時：曲も途中の位置も全部消して、まっさらに戻す */
    public void reset() {
        main.removeCallbacks(watchdog);
        releaseAndroid();
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) { }
        loaded = false;
        queue = new ArrayList<>();
        index = -1;
        lastError = null;
        fire();
    }

    private void onEnd() {
        MediaItem it = current();
        if (it != null && it.video) { stop(); return; }   // 動画は1本で止める
        next();
    }

    private void onVlcError() {
        MediaItem it = current();
        if (it != null && !it.video) {   // VLCでもだめだった曲は飛ばす
            lastError = "再生できませんでした：" + it.file.getName();
            loaded = false;
            errCount++;
            if (errCount < Math.min(queue.size(), 5)) next(); else fire();
            return;
        }
        lastError = "再生できませんでした：" + (it != null ? it.file.getName() : "");
        loaded = false;
        fire();
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
                                pause();   // 電話などが来たら一時停止
                        }, main)
                        .build();
            }
            am.requestAudioFocus(focusReq);
        } catch (Exception ignored) { }
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

    // ---------- マイ設定（3つまで保存） ----------

    public boolean slotUsed(int n) {
        return ctx.getSharedPreferences("eq", 0).getBoolean("s" + n + "_used", false);
    }

    public void saveSlot(int n) {
        SharedPreferences.Editor e = ctx.getSharedPreferences("eq", 0).edit();
        for (int i = 0; i < BANDS; i++) e.putFloat("s" + n + "_b" + i, bands[i]);
        e.putFloat("s" + n + "_bass", bass);
        e.putBoolean("s" + n + "_protect", protect);
        e.putBoolean("s" + n + "_used", true);
        e.apply();
    }

    public boolean loadSlot(int n) {
        SharedPreferences p = ctx.getSharedPreferences("eq", 0);
        if (!p.getBoolean("s" + n + "_used", false)) return false;
        for (int i = 0; i < BANDS; i++) bands[i] = p.getFloat("s" + n + "_b" + i, 0);
        bass = p.getFloat("s" + n + "_bass", 0);
        protect = p.getBoolean("s" + n + "_protect", true);
        applyEq();
        saveEq();
        return true;
    }

    /** アナライザー用：標準エンジンで再生中ならその音の番号、それ以外は0（スマホ全体の音） */
    public int audioSession() {
        try { if (useAndroid && amp != null) return amp.getAudioSessionId(); } catch (Exception ignored) { }
        return 0;
    }

    float gain(int i) {
        return Math.max(-20f, Math.min(20f, bands[i] + BASS_WEIGHT[i] * bass));
    }

    /** 好きな周波数での強さ（となりのバンドの間をなめらかにつなぐ） */
    float gainAt(float hz) {
        if (hz <= BAND_HZ[0]) return gain(0);
        for (int i = 0; i < BANDS - 1; i++) {
            if (hz <= BAND_HZ[i + 1]) {
                double t = Math.log(hz / BAND_HZ[i]) / Math.log(BAND_HZ[i + 1] / BAND_HZ[i]);
                return (float) (gain(i) * (1 - t) + gain(i + 1) * t);
            }
        }
        return gain(BANDS - 1);
    }

    public void applyEq() {
        boolean flat = bass == 0;
        for (float b : bands) if (b != 0) flat = false;
        if (useAndroid) { applyAndroidEq(flat); return; }
        if (mp == null) return;
        try {
            if (flat) {                  // 何もいじっていない時は無加工で通す
                mp.setEqualizer(null);
                return;
            }
            MediaPlayer.Equalizer eq = MediaPlayer.Equalizer.create();
            int n = Math.min(BANDS, MediaPlayer.Equalizer.getBandCount());
            float max = 0;
            for (int i = 0; i < n; i++) {
                float g = gain(i);
                eq.setAmp(i, g);
                if (g > max) max = g;
            }
            eq.setPreAmp(protect ? Math.max(-20f, -max) : 0f);  // 音割れ防止
            mp.setEqualizer(eq);
        } catch (Throwable ignored) { }
    }

    /** 標準の仕組みで再生中のイコライザー（スマホ側の帯域に近い所の値を使う） */
    void applyAndroidEq(boolean flat) {
        if (amp == null) return;
        try {
            if (flat) {
                if (aeq != null) aeq.setEnabled(false);
                return;
            }
            if (aeq == null) aeq = new Equalizer(0, amp.getAudioSessionId());
            short[] range = aeq.getBandLevelRange();
            short nb = aeq.getNumberOfBands();
            for (short b = 0; b < nb; b++) {
                float hz = aeq.getCenterFreq(b) / 1000f;
                int mb = Math.round(gainAt(hz) * 100);
                mb = Math.max(range[0], Math.min(range[1], mb));
                aeq.setBandLevel(b, (short) mb);
            }
            aeq.setEnabled(true);
        } catch (Throwable ignored) { }
    }
}
