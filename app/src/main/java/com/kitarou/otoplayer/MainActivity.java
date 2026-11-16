package com.kitarou.otoplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.media.audiofx.Visualizer;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Player.Listener {

    static final int BG = 0xFF121212, CARD = 0xFF1E1E1E, ACC = 0xFFFF7A00, TXT = 0xFFFFFFFF, SUB = 0xFF9E9E9E;

    Player player;

    // 画面の部品
    FrameLayout frame;
    LinearLayout mainCol, permBox;
    Button tabMusic, tabVideo, tabEq;
    LinearLayout searchRow;
    EditText search;
    ListView list;
    FrameLayout listBox;
    IndexBar indexBar;
    TextView bubble;
    ScrollView eqView;
    TextView status, nowTitle, timeText;
    SeekBar seek;
    Button btnPlay;
    // 動画
    FrameLayout videoBox;
    VLCVideoLayout videoLayout;
    LinearLayout videoCtrl;
    SeekBar vSeek;
    Button vPlay, vOrientBtn, vFullBtn;
    int vOrient = 0;          // 0=縦 1=横 2=自動
    boolean vFull = true;     // true=全画面 false=普通画面
    int insetTop = 0;
    Button[] presetBtns = new Button[4];
    int activePreset = -1;                       // いま押されているおまかせ（-1=なし）
    float[] beforeBands = new float[Player.BANDS];
    float beforeBass = 0;

    int tab = 0; // 0=音楽 1=動画 2=音質
    int view = 0; // 音楽の見方 0=曲 1=アーティスト 2=アルバム 3=フォルダ
    String group = null;          // 選んだアーティスト等
    List<String[]> groups = new ArrayList<>();
    boolean showingGroups = false;
    LinearLayout viewRow;
    Button[] viewBtns = new Button[4];
    Button backBtn;
    List<MediaItem> allAudio = new ArrayList<>(), allVideo = new ArrayList<>();
    List<MediaItem> shown = new ArrayList<>();
    Adapter adapter = new Adapter();
    boolean dragging = false, scanned = false, attached = false;

    // イコライザー画面
    SeekBar[] bandBars = new SeekBar[Player.BANDS];
    TextView[] bandVals = new TextView[Player.BANDS];
    SeekBar bassBar;
    TextView bassVal;
    Switch protectSw;
    SpectrumView spectrum;
    Visualizer viz;
    boolean started = false;
    final Runnable decayer = new Runnable() {
        @Override public void run() {
            if (spectrum == null) return;
            if (!player.isPlaying()) spectrum.decay();
            spectrum.postDelayed(this, 50);
        }
    };

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        player = Player.get(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        setContentView(frame);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        player.addListener(this);
        if (!attached && player.mp != null) { player.mp.attachViews(videoLayout, null, false, false); attached = true; }
        onPlayerChanged();
        started = true;
        updateViz();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasFileAccess()) {
            permBox.setVisibility(View.GONE);
            if (!scanned) scan();
        } else {
            permBox.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onStop() {
        started = false;
        stopViz();
        player.removeListener(this);
        if (attached && player.mp != null) { player.mp.detachViews(); attached = false; }
        super.onStop();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (videoBox.getVisibility() == View.VISIBLE) { closeVideo(); return; }
        if (tab == 0 && group != null) { group = null; filter(); return; }
        moveTaskToBack(true);   // 戻るで閉じても再生を続ける
    }

    // ---------- ファイルへのアクセス ----------

    boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void askFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 1 && hasFileAccess()) { permBox.setVisibility(View.GONE); scan(); }
        if (code == 3) updateViz();
    }

    void scan() {
        scanned = true;
        status.setText("スマホの中を探しています…");
        status.setVisibility(View.VISIBLE);
        new Thread(() -> {
            MediaScanner.Result r = MediaScanner.scan(this);
            runOnUiThread(() -> {
                allAudio = r.audio;
                allVideo = r.video;
                status.setText("音楽 " + allAudio.size() + "曲 ／ 動画 " + allVideo.size() + "本");
                filter();
            });
        }).start();
    }

    String groupOf(MediaItem it) {
        if (view == 1) return it.artistOr();
        if (view == 2) return it.albumOr();
        return it.folder;
    }

    void filter() {
        String q = search.getText().toString().trim().toLowerCase();
        boolean music = tab == 0;
        List<MediaItem> src = music ? allAudio : allVideo;
        shown = new ArrayList<>();
        groups = new ArrayList<>();
        showingGroups = music && view != 0 && group == null;
        if (showingGroups) {
            java.util.TreeMap<String, Integer> m = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (MediaItem it : src) {
                String g = groupOf(it);
                if (!q.isEmpty() && !g.toLowerCase().contains(q) && !it.searchKey.contains(q)) continue;
                Integer c = m.get(g);
                m.put(g, c == null ? 1 : c + 1);
            }
            for (java.util.Map.Entry<String, Integer> e : m.entrySet())
                groups.add(new String[]{e.getKey(), e.getValue() + "曲"});
        } else {
            for (MediaItem it : src) {
                if (music && view != 0 && !groupOf(it).equals(group)) continue;
                if (q.isEmpty() || it.searchKey.contains(q)) shown.add(it);
            }
            if (music && view == 2) {   // アルバムはファイル名順（曲順）に
                shown.sort((a, b) -> a.file.getName().compareToIgnoreCase(b.file.getName()));
            }
        }
        backBtn.setVisibility(music && group != null ? View.VISIBLE : View.GONE);
        if (group != null) backBtn.setText("← " + group);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    String labelAt(int i) {
        return showingGroups ? groups.get(i)[0] : shown.get(i).title;
    }

    /** 索引の文字の所へ一覧を飛ばす */
    void jumpTo(int sec) {
        int n = adapter.getCount();
        if (n == 0) return;
        int best = -1;
        for (int i = 0; i < n; i++) {
            if (IndexBar.sectionOf(labelAt(i)) == sec) { best = i; break; }
        }
        if (best < 0) {   // その文字が無ければ、その次の文字の先頭へ
            int bestSec = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                int s2 = IndexBar.sectionOf(labelAt(i));
                if (s2 > sec && s2 < bestSec) { bestSec = s2; best = i; }
            }
        }
        if (best < 0) best = n - 1;
        list.setSelection(best);
    }

    void updateViewBtns() {
        for (int i = 0; i < 4; i++) setOn(viewBtns[i], view == i);
    }

    // ---------- 画面づくり ----------

    TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    Button button(String s, float sp) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(sp);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF2C2C2C);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    void setOn(Button b, boolean on) {
        GradientDrawable g = (GradientDrawable) b.getBackground();
        g.setColor(on ? ACC : 0xFF2C2C2C);
        b.setTextColor(on ? 0xFF000000 : TXT);
    }

    LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        return p;
    }

    void buildUi() {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);

        mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        frame.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

        // 画面の端（ステータスバー等）に重ならないように
        frame.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom;
            } else {
                top = ins.getSystemWindowInsetTop(); bottom = ins.getSystemWindowInsetBottom();
            }
            insetTop = top;
            mainCol.setPadding(dp(10), top + dp(6), dp(10), bottom + dp(8));
            applyVideoMode();
            videoCtrl.setPadding(dp(10), dp(8), dp(10), bottom + dp(10));
            return ins;
        });

        // タブ
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabMusic = button("♪ 音楽", 17);
        tabVideo = button("▶ 動画", 17);
        tabEq = button("音質", 17);
        for (Button b : new Button[]{tabMusic, tabVideo, tabEq}) {
            LinearLayout.LayoutParams p = lp(0, dp(48), 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            tabs.addView(b, p);
        }
        tabMusic.setOnClickListener(v -> setTab(0));
        tabVideo.setOnClickListener(v -> setTab(1));
        tabEq.setOnClickListener(v -> setTab(2));
        mainCol.addView(tabs);

        // 許可のお願い
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setPadding(dp(8), dp(16), dp(8), dp(8));
        TextView pt = text("スマホの中の音楽と動画を探すために、ファイルへのアクセスを許可してください。\n次の画面で「すべてのファイルへのアクセスを許可」をオンにして、戻ってきてください。", 16, TXT);
        permBox.addView(pt);
        Button pb = button("ファイルへのアクセスを許可する", 17);
        setOn(pb, true);
        LinearLayout.LayoutParams pbp = lp(-1, dp(54), 0);
        pbp.topMargin = dp(12);
        permBox.addView(pb, pbp);
        pb.setOnClickListener(v -> askFileAccess());
        mainCol.addView(permBox);

        // 検索
        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(3), dp(8), dp(3), 0);
        search = new EditText(this);
        search.setHint("曲名・アーティスト・アルバムで検索");
        search.setHintTextColor(SUB);
        search.setTextColor(TXT);
        search.setTextSize(16);
        search.setSingleLine(true);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(CARD);
        sg.setCornerRadius(dp(10));
        search.setBackground(sg);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(); }
            public void afterTextChanged(Editable s) { }
        });
        searchRow.addView(search, lp(0, dp(46), 1));
        Button reload = button("更新", 15);
        LinearLayout.LayoutParams rp = lp(dp(64), dp(46), 0);
        rp.leftMargin = dp(6);
        searchRow.addView(reload, rp);
        reload.setOnClickListener(v -> { if (hasFileAccess()) scan(); });
        mainCol.addView(searchRow);

        viewRow = new LinearLayout(this);
        viewRow.setPadding(0, dp(8), 0, 0);
        String[] vn = {"曲", "アーティスト", "アルバム", "フォルダ"};
        for (int i = 0; i < 4; i++) {
            Button b = button(vn[i], 14);
            final int k = i;
            b.setOnClickListener(v -> { view = k; group = null; search.setText(""); updateViewBtns(); filter(); });
            LinearLayout.LayoutParams p = lp(0, dp(42), i == 1 ? 1.4f : 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            viewRow.addView(b, p);
            viewBtns[i] = b;
        }
        mainCol.addView(viewRow);

        backBtn = button("← 一覧に戻る", 15);
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(v -> { group = null; filter(); });
        LinearLayout.LayoutParams bbp = lp(-1, dp(42), 0);
        bbp.setMargins(dp(3), dp(8), dp(3), 0);
        mainCol.addView(backBtn, bbp);

        status = text("", 13, SUB);
        status.setPadding(dp(6), dp(6), 0, dp(2));
        mainCol.addView(status);

        // 一覧
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setPadding(0, 0, dp(44), 0);
        list.setClipToPadding(false);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            if (showingGroups) { group = groups.get(pos)[0]; search.setText(""); filter(); return; }
            MediaItem it = shown.get(pos);
            if (it.video) openVideo();
            player.play(shown, pos);
        });
        listBox = new FrameLayout(this);
        listBox.addView(list, new FrameLayout.LayoutParams(-1, -1));
        indexBar = new IndexBar(this);
        indexBar.setPadding(0, dp(2), 0, dp(2));
        FrameLayout.LayoutParams ibp = new FrameLayout.LayoutParams(dp(42), -1, Gravity.END);
        listBox.addView(indexBar, ibp);
        bubble = text("", 40, 0xFF000000);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bub = new GradientDrawable();
        bub.setColor(ACC);
        bub.setCornerRadius(dp(16));
        bubble.setBackground(bub);
        bubble.setVisibility(View.GONE);
        listBox.addView(bubble, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
        indexBar.setOnPick((sec, done) -> {
            if (done) { bubble.setVisibility(View.GONE); return; }
            bubble.setText(IndexBar.KEYS[sec]);
            bubble.setVisibility(View.VISIBLE);
            jumpTo(sec);
        });
        mainCol.addView(listBox, lp(-1, 0, 1));

        // 音質（イコライザー）
        eqView = buildEq();
        eqView.setVisibility(View.GONE);
        mainCol.addView(eqView, lp(-1, 0, 1));

        // 再生バー
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        bar.setBackground(bg);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        nowTitle = text("曲を選んでください", 16, TXT);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(nowTitle);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(seekListener());
        bar.addView(seek, lp(-1, dp(36), 0));
        timeText = text("0:00 / 0:00", 13, SUB);
        timeText.setGravity(Gravity.CENTER);
        bar.addView(timeText);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = button("⏮", 22), next = button("⏭", 22);
        btnPlay = button("▶", 24);
        setOn(btnPlay, true);
        prev.setOnClickListener(v -> player.prev());
        next.setOnClickListener(v -> player.next());
        btnPlay.setOnClickListener(v -> {
            if (player.current() == null) {
                if (!shown.isEmpty()) player.play(shown, 0);
                return;
            }
            player.toggle();
        });
        float[] ws = {1, 1.4f, 1};
        Button[] bs = {prev, btnPlay, next};
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams p = lp(0, dp(56), ws[i]);
            p.setMargins(dp(4), dp(6), dp(4), 0);
            btns.addView(bs[i], p);
        }
        bar.addView(btns);
        LinearLayout.LayoutParams barp = lp(-1, -2, 0);
        barp.topMargin = dp(8);
        mainCol.addView(bar, barp);

        // 動画の画面
        videoBox = new FrameLayout(this);
        videoBox.setBackgroundColor(Color.BLACK);
        videoBox.setVisibility(View.GONE);
        videoLayout = new VLCVideoLayout(this);
        videoBox.addView(videoLayout, new FrameLayout.LayoutParams(-1, -1));
        videoCtrl = new LinearLayout(this);
        videoCtrl.setOrientation(LinearLayout.VERTICAL);
        videoCtrl.setBackgroundColor(0xAA000000);
        // 上の段：向き・全画面の切り替え
        LinearLayout vTop = new LinearLayout(this);
        vTop.setOrientation(LinearLayout.HORIZONTAL);
        vOrientBtn = button("", 16);
        vFullBtn = button("", 16);
        vOrientBtn.setOnClickListener(v -> {
            vOrient = (vOrient + 1) % 3;
            getPreferences(MODE_PRIVATE).edit().putInt("vOrient", vOrient).apply();
            applyOrientation();
        });
        vFullBtn.setOnClickListener(v -> {
            vFull = !vFull;
            getPreferences(MODE_PRIVATE).edit().putBoolean("vFull", vFull).apply();
            applyVideoMode();
        });
        LinearLayout.LayoutParams tp1 = lp(0, dp(46), 1), tp2 = lp(0, dp(46), 1);
        tp1.setMargins(0, 0, dp(4), dp(6));
        tp2.setMargins(dp(4), 0, 0, dp(6));
        vTop.addView(vOrientBtn, tp1);
        vTop.addView(vFullBtn, tp2);
        videoCtrl.addView(vTop);
        // 下の段：再生・シーク・閉じる
        LinearLayout vRow = new LinearLayout(this);
        vRow.setOrientation(LinearLayout.HORIZONTAL);
        vRow.setGravity(Gravity.CENTER_VERTICAL);
        vPlay = button("⏸", 20);
        Button vClose = button("✕ 閉じる", 16);
        vSeek = new SeekBar(this);
        vSeek.setMax(1000);
        vSeek.setOnSeekBarChangeListener(seekListener());
        vPlay.setOnClickListener(v -> player.toggle());
        vClose.setOnClickListener(v -> closeVideo());
        vRow.addView(vPlay, lp(dp(60), dp(48), 0));
        vRow.addView(vSeek, lp(0, dp(48), 1));
        vRow.addView(vClose, lp(dp(110), dp(48), 0));
        videoCtrl.addView(vRow);
        vOrient = getPreferences(MODE_PRIVATE).getInt("vOrient", 0);
        vFull = getPreferences(MODE_PRIVATE).getBoolean("vFull", true);
        FrameLayout.LayoutParams vcp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        videoBox.addView(videoCtrl, vcp);
        View tapper = new View(this);
        tapper.setOnClickListener(v -> videoCtrl.setVisibility(
                videoCtrl.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        videoBox.addView(tapper, 1, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));

        setTab(0);
    }

    SeekBar.OnSeekBarChangeListener seekListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) timeText.setText(fmt(player.length() * p / 1000) + " / " + fmt(player.length()));
            }
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                long len = player.length();
                if (len > 0) player.seek(len * s.getProgress() / 1000);
            }
        };
    }

    void setTab(int t) {
        tab = t;
        setOn(tabMusic, t == 0);
        setOn(tabVideo, t == 1);
        setOn(tabEq, t == 2);
        boolean listMode = t != 2;
        listBox.setVisibility(listMode ? View.VISIBLE : View.GONE);
        searchRow.setVisibility(listMode ? View.VISIBLE : View.GONE);
        status.setVisibility(listMode ? View.VISIBLE : View.GONE);
        viewRow.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        if (t != 0) backBtn.setVisibility(View.GONE);
        updateViewBtns();
        eqView.setVisibility(listMode ? View.GONE : View.VISIBLE);
        updateViz();
        if (listMode) filter();
    }

    void openVideo() {
        videoBox.setVisibility(View.VISIBLE);
        videoCtrl.setVisibility(View.VISIBLE);
        applyOrientation();
        applyVideoMode();
    }

    /** 画面の向き：縦 → 横 → 自動 の順に切り替え */
    void applyOrientation() {
        String[] names = {"↻ 向き：縦", "↻ 向き：横", "↻ 向き：自動"};
        vOrientBtn.setText(names[vOrient]);
        if (videoBox.getVisibility() != View.VISIBLE) return;
        int[] o = {ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR};
        setRequestedOrientation(o[vOrient]);
    }

    /** 全画面（上下のバーを隠して画面いっぱい）と普通画面（バーを出して映像全体を表示） */
    @SuppressWarnings("deprecation")
    void applyVideoMode() {
        if (vFullBtn == null) return;
        vFullBtn.setText(vFull ? "▢ 普通画面にする" : "⛶ 全画面にする");
        boolean showing = videoBox.getVisibility() == View.VISIBLE;
        boolean hide = showing && vFull;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (hide) {
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(hide
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                      | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        videoBox.setPadding(0, showing && !vFull ? insetTop : 0, 0, 0);
        if (player.mp != null) {
            try {
                player.mp.setVideoScale(vFull ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                                               : MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            } catch (Throwable ignored) { }
        }
    }

    void closeVideo() {
        MediaItem it = player.current();
        if (it != null && it.video) player.stop();
        videoBox.setVisibility(View.GONE);
        applyVideoMode();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // ---------- 音質画面 ----------

    ScrollView buildEq() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(10), dp(6), dp(10));
        sv.addView(col);

        // アナライザー／イコライザーの切り替え
        LinearLayout modeRow = new LinearLayout(this);
        Button mAna = button("アナライザー", 15);
        Button mEq = button("イコライザー", 15);
        LinearLayout.LayoutParams mp1 = lp(0, dp(42), 1), mp2 = lp(0, dp(42), 1);
        mp1.setMargins(0, 0, dp(3), 0);
        mp2.setMargins(dp(3), 0, 0, 0);
        modeRow.addView(mAna, mp1);
        modeRow.addView(mEq, mp2);
        col.addView(modeRow);
        spectrum = new SpectrumView(this);
        LinearLayout.LayoutParams spp = lp(-1, dp(220), 0);
        spp.setMargins(0, dp(6), 0, dp(14));
        col.addView(spectrum, spp);
        boolean eqStart = getPreferences(MODE_PRIVATE).getBoolean("eqMode", false);
        Runnable[] setMode = new Runnable[2];
        setMode[0] = () -> { spectrum.setEqMode(false); setOn(mAna, true); setOn(mEq, false);
            getPreferences(MODE_PRIVATE).edit().putBoolean("eqMode", false).apply(); };
        setMode[1] = () -> { spectrum.setEqMode(true); setOn(mAna, false); setOn(mEq, true);
            getPreferences(MODE_PRIVATE).edit().putBoolean("eqMode", true).apply(); };
        mAna.setOnClickListener(v -> setMode[0].run());
        mEq.setOnClickListener(v -> setMode[1].run());
        setMode[eqStart ? 1 : 0].run();
        spectrum.setEqListener(new SpectrumView.EqListener() {
            public void onEq(int c, float db) {
                // 8本のつまみ → 10バンド（いちばん低い・高いつまみは2つずつ動かす）
                if (c == 0) { player.bands[0] = db; player.bands[1] = db; }
                else if (c == 7) { player.bands[8] = db; player.bands[9] = db; }
                else player.bands[c + 1] = db;
                player.applyEq();
                clearPreset();
            }
            public void onEqDone() { player.saveEq(); }
        });

        // 重低音
        TextView h1 = text("Bass Plus", 20, ACC);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(h1);
        LinearLayout br = new LinearLayout(this);
        br.setGravity(Gravity.CENTER_VERTICAL);
        bassBar = new SeekBar(this);
        bassBar.setMax(120);           // 0〜12dB
        bassVal = text("", 15, TXT);
        bassVal.setGravity(Gravity.END);
        br.addView(bassBar, lp(0, dp(48), 1));
        br.addView(bassVal, lp(dp(70), -2, 0));
        col.addView(br);
        bassBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                bassVal.setText(String.format(Locale.US, "+%.1f dB", p / 10f));
                if (u) { player.bass = p / 10f; player.applyEq(); clearPreset(); }
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { player.saveEq(); }
        });

        // 音割れ防止
        protectSw = new Switch(this);
        protectSw.setText("音割れ防止（強く上げた分だけ全体の音量を下げます）");
        protectSw.setTextColor(TXT);
        protectSw.setTextSize(14);
        protectSw.setPadding(0, dp(6), 0, dp(14));
        protectSw.setOnCheckedChangeListener((b, on) -> {
            player.protect = on; player.applyEq(); player.saveEq();
        });
        col.addView(protectSw);

        // プリセット
        String[] names = {"フラット", "重低音", "ボーカル", "迫力"};
        float[][] presets = {
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {5, 4, 2, 0, -1, 0, 1, 2, 2, 2},
                {-2, -1, 0, 2, 4, 4, 2, 1, 0, 0},
                {4, 3, 0, -2, -1, 1, 3, 4, 4, 4}};
        float[] presetBass = {0, 6, 0, 3};
        LinearLayout pr = new LinearLayout(this);
        pr.setPadding(0, dp(6), 0, dp(14));
        for (int i = 0; i < names.length; i++) {
            Button b = button(names[i], 15);
            presetBtns[i] = b;
            final int k = i;
            b.setOnClickListener(v -> {
                if (activePreset == k) {
                    // もう一度押したら、押す前の音に戻す
                    System.arraycopy(beforeBands, 0, player.bands, 0, Player.BANDS);
                    player.bass = beforeBass;
                    activePreset = -1;
                } else {
                    if (activePreset < 0) {   // 最初に押した時だけ、元の音を覚えておく
                        System.arraycopy(player.bands, 0, beforeBands, 0, Player.BANDS);
                        beforeBass = player.bass;
                    }
                    System.arraycopy(presets[k], 0, player.bands, 0, Player.BANDS);
                    player.bass = presetBass[k];
                    activePreset = k;
                }
                player.applyEq();
                player.saveEq();
                refreshEq();
                updatePresetBtns();
            });
            LinearLayout.LayoutParams p = lp(0, dp(46), 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            pr.addView(b, p);
        }
        col.addView(pr);

        // マイ設定
        TextView hs = text("マイ設定（3つまで保存）", 20, ACC);
        hs.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(hs);
        TextView hsn = text("「保存」で今の音を記録、「設定1」などを押すと呼び出します。", 13, SUB);
        hsn.setPadding(0, dp(2), 0, dp(4));
        col.addView(hsn);
        for (int n = 0; n < 3; n++) {
            final int k = n;
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(4), 0, dp(4));
            Button load = button("", 16);
            slotBtns[n] = load;
            load.setOnClickListener(v -> {
                if (player.loadSlot(k)) {
                    clearPreset();
                    refreshEq();
                    Toast.makeText(this, "設定" + (k + 1) + "にしました", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "設定" + (k + 1) + "はまだ保存されていません", Toast.LENGTH_SHORT).show();
                }
            });
            Button save = button("保存", 16);
            save.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("今の音を「設定" + (k + 1) + "」に保存しますか？"
                            + (player.slotUsed(k) ? "\n（前に保存した内容は上書きされます）" : ""))
                    .setPositiveButton("保存する", (d, w) -> {
                        player.saveSlot(k);
                        refreshSlots();
                        Toast.makeText(this, "設定" + (k + 1) + "に保存しました", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("やめる", null)
                    .show());
            LinearLayout.LayoutParams p1 = lp(0, dp(50), 1);
            p1.setMargins(dp(3), 0, dp(3), 0);
            LinearLayout.LayoutParams p2 = lp(dp(96), dp(50), 0);
            p2.setMargins(dp(3), 0, dp(3), 0);
            row.addView(load, p1);
            row.addView(save, p2);
            col.addView(row);
        }
        refreshSlots();
        View sp2 = new View(this);
        col.addView(sp2, lp(-1, dp(14), 0));

        TextView note = text("\nすべて0（フラット）にすると、音を一切加工せずに再生します。", 13, SUB);
        col.addView(note);
        refreshEq();
        return sv;
    }

    Button[] slotBtns = new Button[3];

    void updatePresetBtns() {
        for (int i = 0; i < presetBtns.length; i++) if (presetBtns[i] != null) setOn(presetBtns[i], activePreset == i);
    }

    /** 自分でつまみを動かしたら、おまかせの「押されている状態」を解除 */
    void clearPreset() {
        if (activePreset < 0) return;
        activePreset = -1;
        updatePresetBtns();
    }

    void refreshSlots() {
        for (int n = 0; n < 3; n++) {
            if (slotBtns[n] == null) continue;
            boolean used = player.slotUsed(n);
            slotBtns[n].setText("設定" + (n + 1) + (used ? "" : "（空き）"));
            slotBtns[n].setTextColor(used ? TXT : SUB);
        }
    }

    // ---------- アナライザー ----------

    void updateViz() {
        if (started && tab == 2) startViz(); else stopViz();
    }

    void startViz() {
        if (spectrum == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            spectrum.setMessage("アナライザーを出すには許可が必要です");
            if (!askedMic) {
                askedMic = true;
                new AlertDialog.Builder(this)
                        .setMessage("アナライザーで、いま鳴っている音の強さを表示するために「音声の録音」の許可が必要です。\n実際に録音したり、マイクの音を使ったりはしません。")
                        .setPositiveButton("次へ", (d, w) -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3))
                        .setNegativeButton("使わない", null)
                        .show();
            }
            return;
        }
        int session = player.audioSession();
        if (viz != null && vizSession == session) return;
        stopViz();
        try {
            viz = new Visualizer(session);
            viz.setEnabled(false);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer v, byte[] w, int r) { }
                public void onFftDataCapture(Visualizer v, byte[] f, int r) {
                    if (player.isPlaying()) spectrum.setFft(f, r);
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
            viz.setEnabled(true);
            vizSession = session;
            spectrum.setMessage(null);
            spectrum.removeCallbacks(decayer);
            spectrum.post(decayer);
        } catch (Throwable t) {
            viz = null;
            spectrum.setMessage("このスマホではアナライザーを表示できません");
        }
    }

    void stopViz() {
        if (spectrum != null) spectrum.removeCallbacks(decayer);
        if (viz != null) {
            try { viz.setEnabled(false); viz.release(); } catch (Throwable ignored) { }
            viz = null;
        }
    }

    boolean askedMic = false;
    int vizSession = -1;

    void refreshEq() {
        bassBar.setProgress(Math.round(player.bass * 10));
        bassVal.setText(String.format(Locale.US, "+%.1f dB", player.bass));
        protectSw.setChecked(player.protect);
        if (spectrum != null) spectrum.setBands(player.bands);
        for (int i = 0; i < Player.BANDS; i++) {
            if (bandBars[i] == null) continue;
            bandBars[i].setProgress(Math.round(player.bands[i] * 10) + 120);
            bandVals[i].setText(String.format(Locale.US, "%+.1f dB", player.bands[i]));
        }
    }

    // ---------- 再生状態の表示 ----------

    static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public void onPlayerChanged() {
        MediaItem it = player.current();
        boolean playing = player.isPlaying();
        nowTitle.setText(it == null ? "曲を選んでください" : (it.artist.isEmpty() ? it.title : it.title + "  ／  " + it.artist));
        btnPlay.setText(playing ? "⏸" : "▶");
        vPlay.setText(playing ? "⏸" : "▶");
        long len = player.length(), t = player.time();
        if (!dragging) {
            int p = len > 0 ? (int) (t * 1000 / len) : 0;
            seek.setProgress(p);
            vSeek.setProgress(p);
            String st = player.stateText();
            timeText.setText(fmt(t) + " / " + fmt(len) + (st.isEmpty() ? "" : "   " + st));
        }
        if (it != lastItem) { lastItem = it; adapter.notifyDataSetChanged(); }
        if (viz != null && vizSession != player.audioSession()) updateViz();
                String err = player.lastError;
        if (err != null && !err.equals(lastErrShown)) {
            android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show();
        }
        lastErrShown = err;
    }

    String lastErrShown = null;

    MediaItem lastItem = null;

    // ---------- 一覧の表示 ----------

    class Adapter extends BaseAdapter {
        public int getCount() { return showingGroups ? groups.size() : shown.size(); }
        public Object getItem(int i) { return showingGroups ? groups.get(i) : shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row;
            TextView t1, t2;
            if (v == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                t1 = text("", 16, TXT);
                t1.setSingleLine(true);
                t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t2 = text("", 12, SUB);
                t2.setSingleLine(true);
                row.addView(t1);
                row.addView(t2);
                row.setTag(new TextView[]{t1, t2});
            } else {
                row = (LinearLayout) v;
                TextView[] tv = (TextView[]) row.getTag();
                t1 = tv[0];
                t2 = tv[1];
            }
            if (showingGroups) {
                String[] g = groups.get(i);
                t1.setText(g[0]);
                t1.setTextColor(TXT);
                t2.setText(g[1]);
                return row;
            }
            MediaItem it = shown.get(i);
            MediaItem cur = player.current();
            boolean now = cur != null && cur.file.equals(it.file);
            t1.setText((now ? "♪ " : "") + it.title);
            t1.setTextColor(now ? ACC : TXT);
            t2.setText(it.video ? it.folder : it.sub());
            return row;
        }
    }
}
