package com.kitarou.otoplayer;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * アナライザー（いま鳴っている音の高さごとの強さを棒で表示）
 * イコライザーモードでは、同じ画面で8本のつまみを上下に動かして音を調整できる
 */
public class SpectrumView extends View {

    static final int N = 24;
    static final float LO = 45f, HI = 16000f;

    /** イコライザーのつまみ（8本） */
    static final int EQN = 8;
    static final String[] EQ_LABEL = {"60", "125", "250", "500", "1k", "2k", "4k", "8k"};
    static final float EQ_MAX = 12f;

    public interface EqListener {
        void onEq(int ctrl, float db);
        void onEqDone();
    }

    final float[] level = new float[N];
    final float[] peak = new float[N];
    final float[] edges = new float[N + 1];
    final float[] eq = new float[EQN];
    boolean eqMode = false;
    int dragging = -1;
    EqListener listener;

    final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint refl = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pk = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pkGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint haze = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint curveGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint curveFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint knobEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint valText = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    final Path path = new Path();
    final Path fill = new Path();
    String message = null;
    final float density;

    // 描画位置（onSizeChangedで計算）
    float pad, top, bottom, area, reflH, labelY;

    public SpectrumView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // ぼかし（光）を使うため
        for (int i = 0; i <= N; i++) edges[i] = (float) (LO * Math.pow(HI / LO, i / (double) N));

        glow.setColor(0x664FB4FF);
        glow.setMaskFilter(new BlurMaskFilter(6 * density, BlurMaskFilter.Blur.NORMAL));
        edge.setColor(0xFFFFFFFF);
        pk.setColor(0xFFFFFFFF);
        pkGlow.setColor(0xAAB8E4FF);
        pkGlow.setMaskFilter(new BlurMaskFilter(4 * density, BlurMaskFilter.Blur.NORMAL));
        label.setColor(0xFF8FB4E0);
        label.setTextSize(11 * density);
        label.setTextAlign(Paint.Align.CENTER);
        grid.setColor(0x1F9AD8FF);
        grid.setStrokeWidth(1);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(1 * density);
        frame.setColor(0x334FA3F7);

        curve.setStyle(Paint.Style.STROKE);
        curve.setStrokeWidth(2.5f * density);
        curve.setColor(0xFFCFEBFF);
        curve.setStrokeJoin(Paint.Join.ROUND);
        curveGlow.setStyle(Paint.Style.STROKE);
        curveGlow.setStrokeWidth(6 * density);
        curveGlow.setColor(0x884FB4FF);
        curveGlow.setMaskFilter(new BlurMaskFilter(6 * density, BlurMaskFilter.Blur.NORMAL));
        knob.setColor(0xFFFFFFFF);
        knobEdge.setColor(0xFF4FA3F7);
        knobEdge.setMaskFilter(new BlurMaskFilter(5 * density, BlurMaskFilter.Blur.NORMAL));
        track.setColor(0x334FA3F7);
        track.setStrokeWidth(2 * density);
        valText.setColor(0xFFDDF0FF);
        valText.setTextSize(11 * density);
        valText.setTextAlign(Paint.Align.CENTER);
    }

    public void setEqListener(EqListener l) { listener = l; }

    public void setEqMode(boolean on) { eqMode = on; dragging = -1; invalidate(); }

    /** 10バンドの値から、8本のつまみの値を作る */
    public void setBands(float[] b) {
        eq[0] = b[1];
        for (int i = 1; i <= 6; i++) eq[i] = b[i + 1];
        eq[7] = b[8];
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        pad = 8 * density;
        float labelH = 18 * density;
        top = pad + 4 * density;
        float avail = h - labelH - top;
        reflH = avail * 0.16f;
        area = avail - reflH;
        bottom = top + area;
        labelY = h - 5 * density;

        // 背景：下は少し明るい紺 → 上ほど濃紺
        bg.setShader(new LinearGradient(0, h, 0, 0, 0xFF1F3F7A, 0xFF050C20, Shader.TileMode.CLAMP));
        // 奥からほんのり光るもや
        haze.setShader(new RadialGradient(w / 2f, bottom, Math.max(w, h) * 0.6f,
                0x403A86E0, 0x003A86E0, Shader.TileMode.CLAMP));
        // 棒：下はしっかりした青 → 上がるほど薄い青 → てっぺんは白（少し透ける）
        bar.setShader(new LinearGradient(0, bottom, 0, top,
                new int[]{0xD01565E0, 0xD84FA3F7, 0xE6A8D8FF, 0xF5FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        // 床への映り込み
        refl.setShader(new LinearGradient(0, bottom, 0, bottom + reflH,
                0x604FA3F7, 0x004FA3F7, Shader.TileMode.CLAMP));
        // イコライザーの線の下の塗り
        curveFill.setShader(new LinearGradient(0, top, 0, bottom,
                0x554FB4FF, 0x084FB4FF, Shader.TileMode.CLAMP));
    }

    public void setMessage(String m) { message = m; postInvalidate(); }

    /** Visualizerから届いたFFTの値を受け取る */
    public void setFft(byte[] f, int rateMilliHz) {
        message = null;
        int n = f.length / 2;
        float binHz = (rateMilliHz / 1000f) / f.length;
        if (binHz <= 0) return;
        for (int b = 0; b < N; b++) {
            float lo = edges[b], hi = edges[b + 1];
            float mag;
            if (hi - lo < binHz * 2) {
                // 低い音は1つの区切りが細かすぎるので、帯の真ん中の高さを
                // となりの区切りとの間で計算して求める（同じ値にならないように）
                float c = (float) Math.sqrt(lo * hi) / binHz;
                int k = Math.max(1, Math.min(n - 2, (int) c));
                float t = Math.max(0, Math.min(1, c - k));
                float m0 = bin(f, k), m1 = bin(f, k + 1);
                mag = m0 + (m1 - m0) * t;
            } else {
                int k0 = Math.max(1, (int) (lo / binHz));
                int k1 = Math.min(n - 1, Math.max(k0, (int) (hi / binHz)));
                mag = 0;
                for (int k = k0; k <= k1; k++) mag = Math.max(mag, bin(f, k));
            }
            float db = (float) (20 * Math.log10(mag / 128f + 1e-4));
            float v = Math.max(0, Math.min(1, (db + 54) / 54f));
            level[b] = Math.max(v, level[b] - 0.05f);      // 下がる時はゆっくり
            if (level[b] >= peak[b]) peak[b] = level[b];
            else peak[b] = Math.max(0, peak[b] - 0.012f);
        }
        postInvalidate();
    }

    static float bin(byte[] f, int k) {
        float re = f[2 * k], im = f[2 * k + 1];
        return (float) Math.hypot(re, im);
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
        cv.drawRect(r, bg);
        cv.drawRect(r, haze);
        // 目盛りの横線（透けて見えることで奥行きを出す）
        for (int i = 1; i <= 4; i++) {
            float y = top + area * i / 4f;
            cv.drawLine(pad, y, w - pad, y, grid);
        }
        r.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        cv.drawRect(r, frame);

        if (message != null && !eqMode) {
            label.setColor(0xFFB8DCFF);
            label.setTextSize(14 * density);
            cv.drawText(message, w / 2, h / 2, label);
            label.setTextSize(11 * density);
            label.setColor(0xFF8FB4E0);
            return;
        }

        drawBars(cv, w, eqMode ? 0.35f : 1f);

        if (eqMode) {
            drawEq(cv, w);
        } else {
            float[] hz = {60, 250, 1000, 4000, 16000};
            String[] tx = {"60", "250", "1k", "4k", "16k"};
            for (int i = 0; i < hz.length; i++) {
                float pos = (float) (Math.log(hz[i] / LO) / Math.log(HI / LO));
                float x = pad + (w - pad * 2) * pos;
                x = Math.max(pad + 10 * density, Math.min(w - pad - 10 * density, x));
                cv.drawText(tx[i], x, labelY, label);
            }
        }
    }

    void drawBars(Canvas cv, float w, float alpha) {
        int a = (int) (255 * alpha);
        bar.setAlpha(a); glow.setAlpha((int) (0x66 * alpha)); edge.setAlpha(a);
        refl.setAlpha(a); pk.setAlpha(a); pkGlow.setAlpha((int) (0xAA * alpha));
        float slot = (w - pad * 2) / N;
        float gap = Math.max(1.5f * density, slot * 0.2f);
        for (int b = 0; b < N; b++) {
            float x0 = pad + slot * b + gap / 2, x1 = pad + slot * (b + 1) - gap / 2;
            float y = bottom - area * level[b];
            if (level[b] > 0.01f) {
                // 後ろの光
                r.set(x0 - 1.5f * density, y, x1 + 1.5f * density, bottom);
                cv.drawRect(r, glow);
                // 本体
                r.set(x0, y, x1, bottom);
                cv.drawRect(r, bar);
                // てっぺんの白い線
                r.set(x0, y, x1, y + 1.2f * density);
                cv.drawRect(r, edge);
                // 床への映り込み
                float rh = Math.min(reflH, area * level[b] * 0.4f);
                r.set(x0, bottom + 1.5f * density, x1, bottom + 1.5f * density + rh);
                cv.drawRect(r, refl);
            }
            if (peak[b] > 0.01f) {
                float py = bottom - area * peak[b];
                r.set(x0, py - 3.5f * density, x1, py - 1f * density);
                cv.drawRect(r, pkGlow);
                cv.drawRect(r, pk);
            }
        }
    }

    float eqX(int i, float w) {
        float slot = (w - pad * 2) / EQN;
        return pad + slot * (i + 0.5f);
    }

    float eqY(float db) {
        float mid = top + area / 2f;
        return mid - (db / EQ_MAX) * (area / 2f - 8 * density);
    }

    void drawEq(Canvas cv, float w) {
        float mid = top + area / 2f;
        // 0dBの線
        cv.drawLine(pad, mid, w - pad, mid, track);
        // 各つまみの縦の通り道
        for (int i = 0; i < EQN; i++) {
            float x = eqX(i, w);
            cv.drawLine(x, eqY(EQ_MAX), x, eqY(-EQ_MAX), track);
        }
        // なめらかな線でつなぐ
        path.reset();
        float px = eqX(0, w), py = eqY(eq[0]);
        path.moveTo(pad, py);
        path.lineTo(px, py);
        for (int i = 1; i < EQN; i++) {
            float x = eqX(i, w), y = eqY(eq[i]);
            float cx = (px + x) / 2;
            path.cubicTo(cx, py, cx, y, x, y);
            px = x; py = y;
        }
        path.lineTo(w - pad, py);
        fill.reset();
        fill.addPath(path);
        fill.lineTo(w - pad, bottom);
        fill.lineTo(pad, bottom);
        fill.close();
        cv.drawPath(fill, curveFill);
        cv.drawPath(path, curveGlow);
        cv.drawPath(path, curve);
        // つまみ（四角）
        for (int i = 0; i < EQN; i++) {
            float x = eqX(i, w), y = eqY(eq[i]);
            float s = (i == dragging ? 11 : 8) * density;
            r.set(x - s - 2 * density, y - s - 2 * density, x + s + 2 * density, y + s + 2 * density);
            cv.drawRect(r, knobEdge);
            r.set(x - s, y - s, x + s, y + s);
            cv.drawRect(r, knob);
            // 値
            if (i == dragging || Math.abs(eq[i]) >= 0.05f) {
                String t = String.format(Locale.US, "%+.1f", eq[i]);
                float ty = eq[i] >= 0 ? y + s + 13 * density : y - s - 5 * density;
                cv.drawText(t, x, ty, valText);
            }
            cv.drawText(EQ_LABEL[i], x, labelY, label);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!eqMode) return false;
        float w = getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);   // 画面のスクロールを止める
                float slot = (w - pad * 2) / EQN;
                dragging = Math.max(0, Math.min(EQN - 1, (int) ((e.getX() - pad) / slot)));
                setFromY(e.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging >= 0) setFromY(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                dragging = -1;
                invalidate();
                if (listener != null) listener.onEqDone();
                return true;
        }
        return true;
    }

    void setFromY(float y) {
        float mid = top + area / 2f;
        float db = (mid - y) / (area / 2f - 8 * density) * EQ_MAX;
        db = Math.max(-EQ_MAX, Math.min(EQ_MAX, db));
        db = Math.round(db * 2) / 2f;              // 0.5dB刻み
        if (Math.abs(db) < 0.75f) db = 0;          // 0の近くはぴたっと0に
        if (db != eq[dragging]) {
            eq[dragging] = db;
            if (listener != null) listener.onEq(dragging, db);
        }
        invalidate();
    }
}
