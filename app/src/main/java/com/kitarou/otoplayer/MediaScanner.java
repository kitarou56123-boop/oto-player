package com.kitarou.otoplayer;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** スマホの中の音楽・動画ファイルを探す */
public class MediaScanner {

    static final Set<String> AUDIO = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "alac",
            "aiff", "aif", "aifc", "dsf", "dff", "wv", "mka", "mpc", "tta", "ac3", "dts",
            "amr", "mp2", "m4b", "caf", "au", "spx", "tak"));

    static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "wmv", "mov", "flv", "webm", "m4v", "3gp", "3g2", "ts", "m2ts",
            "mts", "mpg", "mpeg", "vob", "rmvb", "rm", "asf", "ogv", "divx", "f4v", "mxf"));

    public static class Result {
        public final List<MediaItem> audio = new ArrayList<>();
        public final List<MediaItem> video = new ArrayList<>();
    }

    public static Result scan(Context c) {
        Set<String> roots = new LinkedHashSet<>();
        roots.add(Environment.getExternalStorageDirectory().getAbsolutePath());
        File[] dirs = c.getExternalFilesDirs(null);
        if (dirs != null) {
            for (File f : dirs) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int k = p.indexOf("/Android/");
                if (k > 0) roots.add(p.substring(0, k));
            }
        }
        Result r = new Result();
        for (String root : roots) walk(new File(root), r, 0, true);
        java.util.Comparator<MediaItem> cmp = (a, b) -> a.title.compareToIgnoreCase(b.title);
        r.audio.sort(cmp);
        r.video.sort(cmp);
        return r;
    }

    private static void walk(File dir, Result r, int depth, boolean top) {
        if (depth > 14) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (top && name.equals("Android")) continue;
                walk(f, r, depth + 1, false);
            } else {
                int dot = name.lastIndexOf('.');
                if (dot < 0) continue;
                String ext = name.substring(dot + 1).toLowerCase();
                if (AUDIO.contains(ext)) r.audio.add(new MediaItem(f, false));
                else if (VIDEO.contains(ext)) r.video.add(new MediaItem(f, true));
            }
        }
    }
}
