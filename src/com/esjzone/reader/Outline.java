package com.esjzone.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of the detail page's table of contents.
 *
 * <p>The site expresses hierarchy with three different elements (see DetailParser), so a volume
 * and a chapter end up in the same tree shape.</p>
 */
public final class Outline {

    public final boolean volume;
    /** Not final: the downloader rewrites it when converting traditional to simplified. */
    public String title;
    /** Chapter pages only; empty for volumes. */
    public final String url;
    public final int level;
    public final ArrayList<Outline> children = new ArrayList<Outline>();

    private Outline(boolean volume, String title, String url, int level) {
        this.volume = volume;
        this.title = title;
        this.url = url;
        this.level = level;
    }

    public static Outline volume(String title, int level) {
        return new Outline(true, title, "", level);
    }

    public static Outline chapter(String title, String url, int level) {
        return new Outline(false, title, url, level);
    }

    /** All chapter nodes under this node, depth first, in reading order. */
    public void collectChapters(List<Outline> out) {
        if (!volume) {
            out.add(this);
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).collectChapters(out);
        }
    }

    public int chapterCount() {
        ArrayList<Outline> holder = new ArrayList<Outline>();
        collectChapters(holder);
        return holder.size();
    }

    @Override
    public String toString() {
        return (volume ? "[卷] " : "") + title;
    }
}
