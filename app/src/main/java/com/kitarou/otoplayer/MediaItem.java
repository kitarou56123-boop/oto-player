package com.kitarou.otoplayer;

import java.io.File;

public class MediaItem {
    public final File file;
    public final boolean video;
    public String title;
    public String artist = "";
    public String album = "";
    public final String folder;
    public String searchKey;

    public MediaItem(File f, boolean video) {
        this.file = f;
        this.video = video;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        this.title = dot > 0 ? n.substring(0, dot) : n;
        File p = f.getParentFile();
        this.folder = p != null ? p.getName() : "";
        updateKey();
    }

    public void updateKey() {
        searchKey = (title + " " + artist + " " + album + " " + folder + " " + file.getName()).toLowerCase();
    }

    /** 2行目に出す説明（アーティスト・アルバム、なければフォルダ） */
    public String sub() {
        StringBuilder b = new StringBuilder();
        if (!artist.isEmpty()) b.append(artist);
        if (!album.isEmpty()) { if (b.length() > 0) b.append("  ・  "); b.append(album); }
        if (b.length() == 0) b.append(folder);
        return b.toString();
    }

    public String artistOr() { return artist.isEmpty() ? "（アーティスト不明）" : artist; }
    public String albumOr() { return album.isEmpty() ? "（アルバム不明）" : album; }
}
