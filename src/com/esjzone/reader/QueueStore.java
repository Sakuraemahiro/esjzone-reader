package com.esjzone.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Persists the download queue so a job survives the app being closed or the process killed.
 *
 * <p>A job records which chapters the user picked; an empty selection means "every chapter",
 * which is also what the 全部下载 button uses.</p>
 */
public final class QueueStore {

    public static final class Job {
        public String bookId = "";
        public String title = "";
        public String detailUrl = "";
        public String latestTitle = "";
        public String updated = "";
        /** Explicit chapter URLs; empty means all chapters. */
        public final ArrayList<String> chapterUrls = new ArrayList<String>();
        public long requestedAt;
        public int chaptersTotal;
        public boolean done;
        /** Queued as a text-only job; restored on resume. */
        public boolean textOnly;

        public Book toBook() {
            return new Book(bookId, title, detailUrl, latestTitle, "", "", updated);
        }

        public boolean allChapters() {
            return chapterUrls.isEmpty();
        }
    }

    private static final String PREFS = "esjzone_queue";
    private static final String KEY_JOBS = "jobs";

    private final SharedPreferences prefs;

    public QueueStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void put(Job job) {
        ArrayList<Job> jobs = loadRaw();
        boolean replaced = false;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).bookId.equals(job.bookId)) {
                jobs.set(i, job);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            jobs.add(job);
        }
        saveRaw(jobs);
    }

    public synchronized void remove(String bookId) {
        ArrayList<Job> jobs = loadRaw();
        for (int i = jobs.size() - 1; i >= 0; i--) {
            if (jobs.get(i).bookId.equals(bookId)) {
                jobs.remove(i);
            }
        }
        saveRaw(jobs);
    }

    /** Jobs that were queued but never finished, newest request first. */
    public synchronized ArrayList<Job> pending() {
        ArrayList<Job> pending = new ArrayList<Job>();
        ArrayList<Job> jobs = loadRaw();
        for (int i = 0; i < jobs.size(); i++) {
            if (!jobs.get(i).done) {
                pending.add(jobs.get(i));
            }
        }
        return pending;
    }

    public synchronized ArrayList<Job> all() {
        return loadRaw();
    }

    // ---------------------------------------------------------------- json

    private ArrayList<Job> loadRaw() {
        ArrayList<Job> jobs = new ArrayList<Job>();
        String raw = prefs.getString(KEY_JOBS, "");
        if (raw.length() == 0) {
            return jobs;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                Job job = new Job();
                job.bookId = object.optString("id", "");
                job.title = object.optString("title", "");
                job.detailUrl = object.optString("detail", "");
                job.latestTitle = object.optString("latest", "");
                job.updated = object.optString("updated", "");
                job.requestedAt = object.optLong("at", 0L);
                job.chaptersTotal = object.optInt("total", 0);
                job.done = object.optBoolean("done", false);
                job.textOnly = object.optBoolean("textOnly", false);
                JSONArray chapters = object.optJSONArray("chapters");
                if (chapters != null) {
                    for (int c = 0; c < chapters.length(); c++) {
                        job.chapterUrls.add(chapters.optString(c, ""));
                    }
                }
                if (job.bookId.length() > 0) {
                    jobs.add(job);
                }
            }
        } catch (JSONException malformed) {
            prefs.edit().remove(KEY_JOBS).apply();
        }
        return jobs;
    }

    private void saveRaw(ArrayList<Job> jobs) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            JSONObject object = new JSONObject();
            try {
                object.put("id", job.bookId);
                object.put("title", job.title);
                object.put("detail", job.detailUrl);
                object.put("latest", job.latestTitle);
                object.put("updated", job.updated);
                object.put("at", job.requestedAt);
                object.put("total", job.chaptersTotal);
                object.put("done", job.done);
                object.put("textOnly", job.textOnly);
                JSONArray chapters = new JSONArray();
                for (int c = 0; c < job.chapterUrls.size(); c++) {
                    chapters.put(job.chapterUrls.get(c));
                }
                object.put("chapters", chapters);
            } catch (JSONException impossible) {
                continue;
            }
            array.put(object);
        }
        prefs.edit().putString(KEY_JOBS, array.toString()).apply();
    }
}
