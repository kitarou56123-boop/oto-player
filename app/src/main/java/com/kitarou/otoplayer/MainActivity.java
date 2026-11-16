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
    Button vPlay;

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
            mainCol.setPadding(dp(10), top + dp(6), dp(10), bottom + dp(8));
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
        list.setPadding(0, 0, dp(30), 0);
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
        indexBar.setPadding(0, dp(4), 0, dp(4));
        FrameLayout.LayoutParams ibp = new FrameLayout.LayoutParams(dp(30), -1, Gravity.END);
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
        videoCtrl.setOrientation(LinearLayout.HORIZONTAL);
        videoCtrl.setGravity(Gravity.CENTER_VERTICAL);
        videoCtrl.setBackgroundColor(0xAA000000);
        vPlay = button("⏸", 20);
        Button vClose = button("✕ 閉じる", 16);
        vSeek = new SeekBar(this);
        vSeek.setMax(1000);
        vSeek.setOnSeekBarChangeListener(seekListener());
        vPlay.setOnClickListener(v -> player.toggle());
        vClose.setOnClickListener(v -> closeVideo());
        videoCtrl.addView(vPlay, lp(dp(60), dp(48), 0));
        videoCtrl.addView(vSeek, lp(0, dp(48), 1));
        videoCtrl.addView(vClose, lp(dp(110), dp(48), 0));
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
        if (listMode) filter();
    }

    void openVideo() {
        videoBox.setVisibility(View.VISIBLE);
        videoCtrl.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }

    void closeVideo() {
        MediaItem it = player.current();
        if (it != null && it.video) player.stop();
        videoBox.setVisibility(View.GONE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // ---------- 音質画面 ----------

    ScrollView buildEq() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(10), dp(6), dp(10));
        sv.addView(col);

        // 重低音
        TextView h1 = text("重低音", 20, ACC);
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
                if (u) { player.bass = p / 10f; player.applyEq(); }
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
        TextView h2 = text("おまかせ", 20, ACC);
        h2.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(h2);
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
            final int k = i;
            b.setOnClickListener(v -> {
                System.arraycopy(presets[k], 0, player.bands, 0, Player.BANDS);
                player.bass = presetBass[k];
                player.applyEq();
                player.saveEq();
                refreshEq();
            });
            LinearLayout.LayoutParams p = lp(0, dp(46), 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            pr.addView(b, p);
        }
        col.addView(pr);

        // 10バンド
        TextView h3 = text("イコライザー（10バンド）", 20, ACC);
        h3.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(h3);
        int n = Math.min(Player.BANDS, MediaPlayer.Equalizer.getBandCount());
        for (int i = 0; i < n; i++) {
            float f = MediaPlayer.Equalizer.getBandFrequency(i);
            String label = f >= 1000 ? (f % 1000 == 0 ? (int) (f / 1000) + "kHz" : String.format(Locale.US, "%.1fkHz", f / 1000)) : (int) f + "Hz";
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lb = text(label, 14, SUB);
            row.addView(lb, lp(dp(64), -2, 0));
            SeekBar sb = new SeekBar(this);
            sb.setMax(240);            // -12〜+12dB
            TextView val = text("", 14, TXT);
            val.setGravity(Gravity.END);
            row.addView(sb, lp(0, dp(46), 1));
            row.addView(val, lp(dp(72), -2, 0));
            final int k = i;
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    float g = (p - 120) / 10f;
                    val.setText(String.format(Locale.US, "%+.1f dB", g));
                    if (u) { player.bands[k] = g; player.applyEq(); }
                }
                public void onStartTrackingTouch(SeekBar s) { }
                public void onStopTrackingTouch(SeekBar s) { player.saveEq(); }
            });
            bandBars[i] = sb;
            bandVals[i] = val;
            col.addView(row);
        }
        TextView note = text("\nすべて0（フラット）にすると、音を一切加工せずに再生します。", 13, SUB);
        col.addView(note);
        refreshEq();
        return sv;
    }

    void refreshEq() {
        bassBar.setProgress(Math.round(player.bass * 10));
        bassVal.setText(String.format(Locale.US, "+%.1f dB", player.bass));
        protectSw.setChecked(player.protect);
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
