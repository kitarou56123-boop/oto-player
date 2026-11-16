package com.kitarou.otoplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.text.Normalizer;

/** 一覧の右端に出す「# A B C … Z あ ア 漢」の索引。触った文字の所へ一気に飛ぶ */
public class IndexBar extends View {

    public static final String[] KEYS = buildKeys();

    static String[] buildKeys() {
        String[] k = new String[30];
        k[0] = "#";
        for (int i = 0; i < 26; i++) k[i + 1] = String.valueOf((char) ('A' + i));
        k[27] = "あ";
        k[28] = "ア";
        k[29] = "漢";
        return k;
    }

    /** 名前の1文字目がどの索引に入るか */
    public static int sectionOf(String name) {
        if (name == null || name.isEmpty()) return 0;
        String n = Normalizer.normalize(name.substring(0, Math.min(2, name.length())), Normalizer.Form.NFKC);
        char c = Character.toUpperCase(n.charAt(0));
        if (c >= 'A' && c <= 'Z') return c - 'A' + 1;
        if (c >= 0x3041 && c <= 0x309F) return 27;    // ひらがな
        if (c >= 0x30A1 && c <= 0x30FF) return 28;    // カタカナ
        if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) return 29;
        return 0;
    }

    public interface OnPick { void onPick(int section, boolean done); }

    OnPick listener;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    int touched = -1;

    public IndexBar(Context c) {
        super(c);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
    }

    public void setOnPick(OnPick l) { listener = l; }

    @Override
    protected void onDraw(Canvas cv) {
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float step = h / KEYS.length;
        paint.setTextSize(Math.min(step * 0.85f, getWidth() * 0.6f));
        float x = getWidth() / 2f;
        for (int i = 0; i < KEYS.length; i++) {
            paint.setColor(i == touched ? MainActivity.ACC : 0xFFBDBDBD);
            float y = getPaddingTop() + step * i + step / 2f - (paint.descent() + paint.ascent()) / 2f;
            cv.drawText(KEYS[i], x, y, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        int i = (int) ((e.getY() - getPaddingTop()) / h * KEYS.length);
        i = Math.max(0, Math.min(KEYS.length - 1, i));
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            if (i != touched) {
                touched = i;
                invalidate();
                if (listener != null) listener.onPick(i, false);
            }
            setBackgroundColor(0x33FFFFFF);
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            touched = -1;
            setBackgroundColor(0);
            invalidate();
            if (listener != null) listener.onPick(i, true);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
