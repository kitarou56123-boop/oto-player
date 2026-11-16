package com.kitarou.otoplayer;

import java.io.File;

public class MediaItem {
    public final File file;
    public final boolean video;
    public final String title;
    public final String folder;
    public final String searchKey;

    public MediaItem(File f, boolean video) {
        this.file = f;
        this.video = video;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        this.title = dot > 0 ? n.substring(0, dot) : n;
        File p = f.getParentFile();
        this.folder = p != null ? p.getName() : "";
        this.searchKey = (title + " " + folder).toLowerCase();
    }
}
