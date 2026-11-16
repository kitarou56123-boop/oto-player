package com.kitarou.otoplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.RectF;
import android.view.View;

/** アナライザー（いま鳴っている音の高さごとの強さを棒で表示） */
public class SpectrumView extends View {

    static final int N = 24;
    static final float LO = 30f, HI = 16000f;

    final float[] level = new float[N];
    final float[] peak = new float[N];
    final float[] edges = new float[N + 1];
    final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pk = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    String message = null;
    final float density;

    public SpectrumView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i <= N; i++) edges[i] = (float) (LO * Math.pow(HI / LO, i / (double) N));
        bar.setColor(0xFF9AD8FF);              // 薄いブルー
        pk.setColor(0xFFE3F5FF);               // てっぺんの線（さらに薄い青）
        label.setColor(0xFF7FA6D6);
        label.setTextSize(11 * density);
        label.setTextAlign(Paint.Align.CENTER);
        bg.setColor(0xFF0A1A3F);               // 背景：濃紺
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // 棒：下は少し濃い水色 → 上ほど薄いブルー
        bar.setShader(new LinearGradient(0, h, 0, 0, 0xFF5BB8F0, 0xFFC8ECFF, Shader.TileMode.CLAMP));
    }

    public void setMessage(String m) { message = m; postInvalidate(); }

    /** Visualizerから届いたFFTの値を受け取る */
    public void setFft(byte[] f, int rateMilliHz) {
        message = null;
        int n = f.length / 2;
        float binHz = (rateMilliHz / 1000f) / f.length;
        if (binHz <= 0) return;
        for (int b = 0; b < N; b++) {
            int k0 = Math.max(1, (int) (edges[b] / binHz));
            int k1 = Math.min(n - 1, Math.max(k0, (int) (edges[b + 1] / binHz)));
            float mag = 0;
            for (int k = k0; k <= k1; k++) {
                float re = f[2 * k], im = f[2 * k + 1];
                mag = Math.max(mag, (float) Math.hypot(re, im));
            }
            float db = (float) (20 * Math.log10(mag / 128f + 1e-4));
            float v = Math.max(0, Math.min(1, (db + 54) / 54f));
            level[b] = Math.max(v, level[b] - 0.05f);      // 下がる時はゆっくり
            if (level[b] >= peak[b]) peak[b] = level[b];
            else peak[b] = Math.max(0, peak[b] - 0.012f);
        }
        postInvalidate();
    }

    /** 止まった時に棒を下げる */
    public void decay() {
        boolean any = false;
        for (int b = 0; b < N; b++) {
            level[b] = Math.max(0, level[b] - 0.08f);
            peak[b] = Math.max(0, peak[b] - 0.03f);
            if (level[b] > 0 || peak[b] > 0) any = true;
        }
        if (any) postInvalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        r.set(0, 0, w, h);
        cv.drawRoundRect(r, 12 * density, 12 * density, bg);
        float pad = 8 * density, labelH = 16 * density;
        float top = pad, bottom = h - labelH - pad / 2;
        float area = bottom - top;
        if (message != null) {
            label.setColor(0xFFB8DCFF);
            label.setTextSize(14 * density);
            cv.drawText(message, w / 2, h / 2, label);
            label.setTextSize(11 * density);
            label.setColor(0xFF9E9E9E);
            return;
        }
        float slot = (w - pad * 2) / N;
        float gap = Math.max(1.5f * density, slot * 0.18f);
        for (int b = 0; b < N; b++) {
            float x0 = pad + slot * b + gap / 2, x1 = pad + slot * (b + 1) - gap / 2;
            float y = bottom - area * level[b];
            r.set(x0, y, x1, bottom);
            cv.drawRoundRect(r, 2 * density, 2 * density, bar);
            float py = bottom - area * peak[b];
            r.set(x0, py - 2.5f * density, x1, py);
            cv.drawRect(r, pk);
        }
        float[] hz = {60, 250, 1000, 4000, 16000};
        String[] tx = {"60", "250", "1k", "4k", "16k"};
        for (int i = 0; i < hz.length; i++) {
            float pos = (float) (Math.log(hz[i] / LO) / Math.log(HI / LO));
            float x = pad + (w - pad * 2) * pos;
            x = Math.max(pad + 10 * density, Math.min(w - pad - 10 * density, x));
            cv.drawText(tx[i], x, h - pad / 2, label);
        }
    }
}
