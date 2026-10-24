package com.esjzone.reader;

import java.util.ArrayList;

/** Everything the downloader needs from a /detail/&lt;id&gt;.html page. */
public final class BookDetail {

    public String id = "";
    public String title = "";
    public String author = "";
    public String genre = "";
    public String updated = "";
    /** Lines the site shows that we do not model, such as "Web生肉: ...". */
    public final ArrayList<String> extra = new ArrayList<String>();
    public String coverUrl = "";
    public String description = "";
    public final ArrayList<Outline> outline = new ArrayList<Outline>();

    public int chapterCount() {
        int total = 0;
        for (int i = 0; i < outline.size(); i++) {
            total += outline.get(i).chapterCount();
        }
        return total;
    }

    /** "《title》author", sanitised for use as a file name. */
    public String fileName() {
        String base = title.trim();
        if (base.length() == 0) {
            base = "未命名";
        }
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length() && sb.length() < 90; i++) {
            char c = base.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim() + ".epub";
    }
}
