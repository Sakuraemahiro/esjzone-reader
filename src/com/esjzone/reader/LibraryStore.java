package com.esjzone.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Records of books that were downloaded successfully, for the 已下载 page. */
public final class LibraryStore {

    public static final class Item {
        public String bookId = "";
        public String title = "";
        public String author = "";
        public String uri = "";
        public String location = "";
        public long bytes;
        public int chapters;
        public int images;
        public long finishedAt;

        public String sizeText() {
            return Downloader.humanBytes(bytes);
        }
    }

    private static final String PREFS = "esjzone_library";
    private static final String KEY_ITEMS = "items";

    private final SharedPreferences prefs;

    public LibraryStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void add(Item item) {
        ArrayList<Item> items = all();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).bookId.equals(item.bookId)) {
                items.remove(i);
            }
        }
        items.add(0, item);
        save(items);
    }

    public synchronized void remove(String bookId) {
        ArrayList<Item> items = all();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).bookId.equals(bookId)) {
                items.remove(i);
            }
        }
        save(items);
    }

    public synchronized Item find(String bookId) {
        ArrayList<Item> items = all();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).bookId.equals(bookId)) {
                return items.get(i);
            }
        }
        return null;
    }

    public synchronized ArrayList<Item> all() {
        ArrayList<Item> items = new ArrayList<Item>();
        String raw = prefs.getString(KEY_ITEMS, "");
        if (raw.length() == 0) {
            return items;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                Item item = new Item();
                item.bookId = object.optString("id", "");
                item.title = object.optString("title", "");
                item.author = object.optString("author", "");
                item.uri = object.optString("uri", "");
                item.location = object.optString("path", "");
                item.bytes = object.optLong("bytes", 0L);
                item.chapters = object.optInt("chapters", 0);
                item.images = object.optInt("images", 0);
                item.finishedAt = object.optLong("at", 0L);
                if (item.bookId.length() > 0) {
                    items.add(item);
                }
            }
        } catch (Exception malformed) {
            prefs.edit().remove(KEY_ITEMS).apply();
        }
        return items;
    }

    private void save(ArrayList<Item> items) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            JSONObject object = new JSONObject();
            try {
                object.put("id", item.bookId);
                object.put("title", item.title);
                object.put("author", item.author);
                object.put("uri", item.uri);
                object.put("path", item.location);
                object.put("bytes", item.bytes);
                object.put("chapters", item.chapters);
                object.put("images", item.images);
                object.put("at", item.finishedAt);
            } catch (Exception impossible) {
                continue;
            }
            array.put(object);
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
