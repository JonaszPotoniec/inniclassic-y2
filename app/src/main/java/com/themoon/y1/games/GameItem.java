package com.themoon.y1.games;

import java.io.File;

public class GameItem {
    public final String title;
    public final File bundleDir;
    public final File iconFile;
    public final String version;

    public GameItem(String title, File bundleDir, File iconFile, String version) {
        this.title = title;
        this.bundleDir = bundleDir;
        this.iconFile = iconFile;
        this.version = version;
    }

    public String getTitle() {
        return title;
    }

    public File getBundleDir() {
        return bundleDir;
    }

    public File getIconFile() {
        return iconFile;
    }
}
