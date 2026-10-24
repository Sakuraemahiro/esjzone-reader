package com.esjzone.reader;

/** One row of the "我的收藏" list. */
public final class Book {

    public final String id;
    public final String title;
    public final String detailUrl;
    public final String latestTitle;
    public final String latestUrl;
    public final String lastViewed;
    public final String updated;

    public Book(String id, String title, String detailUrl, String latestTitle, String latestUrl,
                String lastViewed, String updated) {
        this.id = id;
        this.title = title;
        this.detailUrl = detailUrl;
        this.latestTitle = latestTitle == null ? "" : latestTitle;
        this.latestUrl = latestUrl == null ? "" : latestUrl;
        this.lastViewed = lastViewed == null ? "" : lastViewed;
        this.updated = updated == null ? "" : updated;
    }

    /** Stable identity for maps and the download history. */
    public String key() {
        return id;
    }

    @Override
    public String toString() {
        return title;
    }
}
