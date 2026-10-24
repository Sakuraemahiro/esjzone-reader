package com.esjzone.reader;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Pure planning for the split tool: decides which files to write, in which order, holding which
 * chapters, and what each one is called.
 *
 * <p>Deliberately free of Android types so the chunking and naming can be tested on a desktop
 * JVM instead of only by tapping through the UI.</p>
 */
public final class SplitPlan {

    public static final int EACH = 0;
    public static final int GROUP = 1;
    public static final int MERGE = 2;

    public static final int DEFAULT_GROUP = 10;
    public static final int MIN_GROUP = 2;
    public static final int MAX_GROUP = 500;
    private static final int MAX_NAME = 120;

    /** One output file. */
    public static final class Job {
        public final String fileName;
        public final String title;
        public final ArrayList<EpubFile.Chapter> chapters;

        Job(String fileName, String title, ArrayList<EpubFile.Chapter> chapters) {
            this.fileName = fileName;
            this.title = title;
            this.chapters = chapters;
        }

        @Override
        public String toString() {
            return fileName + "  (" + chapters.size() + " 章)";
        }
    }

    /**
     * @param chapters  the chosen chapters, in reading order
     * @param positions their index inside the whole book, used to name grouped files
     * @param mode      {@link #EACH}, {@link #GROUP} or {@link #MERGE}
     * @param perFile   chapters per file for {@link #GROUP}; clamped to a sane range
     */
    public static ArrayList<Job> plan(String base, ArrayList<EpubFile.Chapter> chapters,
                                      ArrayList<Integer> positions, int mode, int perFile) {
        ArrayList<Job> jobs = new ArrayList<Job>();
        if (chapters.isEmpty()) {
            return jobs;
        }
        if (mode == MERGE) {
            jobs.add(new Job(trim(base + " - 拆分合并.epub"), base,
                    new ArrayList<EpubFile.Chapter>(chapters)));
            return jobs;
        }
        if (mode == GROUP) {
            int size = clamp(perFile);
            for (int start = 0; start < chapters.size(); start += size) {
                int end = Math.min(start + size, chapters.size());
                ArrayList<EpubFile.Chapter> chunk =
                        new ArrayList<EpubFile.Chapter>(chapters.subList(start, end));
                String range = String.format(Locale.US, "%03d-%03d",
                        positions.get(start) + 1, positions.get(end - 1) + 1);
                jobs.add(new Job(trim(base + " - " + range + ".epub"),
                        base + "（" + range + "）", chunk));
            }
            return jobs;
        }
        for (int i = 0; i < chapters.size(); i++) {
            EpubFile.Chapter chapter = chapters.get(i);
            ArrayList<EpubFile.Chapter> one = new ArrayList<EpubFile.Chapter>();
            one.add(chapter);
            jobs.add(new Job(trim(base + " - " + sanitize(chapter.title) + ".epub"),
                    base + " - " + chapter.title, one));
        }
        return jobs;
    }

    public static int clamp(int perFile) {
        if (perFile < MIN_GROUP) {
            return MIN_GROUP;
        }
        if (perFile > MAX_GROUP) {
            return MAX_GROUP;
        }
        return perFile;
    }

    /** Parses a user-typed group size, falling back to the default. */
    public static int parseGroupSize(String raw) {
        try {
            return clamp(Integer.parseInt(raw == null ? "" : raw.trim()));
        } catch (NumberFormatException error) {
            return DEFAULT_GROUP;
        }
    }

    /** Drops characters a file name cannot hold, and keeps the length in check. */
    public static String sanitize(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < 80; i++) {
            char c = text.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String name = sb.toString().trim();
        return name.length() == 0 ? "未命名" : name;
    }

    private static String trim(String name) {
        return name.length() <= MAX_NAME ? name
                : name.substring(0, MAX_NAME - 4) + ".epub";
    }

    private SplitPlan() {
    }
}
