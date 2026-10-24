package com.esjzone.reader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Lets the user pick which chapters to download.
 *
 * <p>Opened from the favourites list, it first fetches the detail page so the real table of
 * contents can be shown; 全部下载 skips the selection entirely and is what the plain tap used
 * to do.</p>
 */
public class ChapterPickerActivity extends Activity {

    public static final String EXTRA_ID = "bookId";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_DETAIL = "detailUrl";
    public static final String EXTRA_LATEST = "latest";
    public static final String EXTRA_UPDATED = "updated";

    private static final class Row {
        final boolean volume;
        final String title;
        final Outline chapter;
        final int level;
        final ArrayList<String> urls = new ArrayList<String>();

        Row(boolean volume, String title, Outline chapter, int level) {
            this.volume = volume;
            this.title = title;
            this.chapter = chapter;
            this.level = level;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private final HashSet<String> selected = new HashSet<String>();

    private Book book;
    private BookDetail detail;
    private TextView titleView;
    private TextView statusView;
    private ProgressBar loading;
    private ListView list;
    private CheckBox textOnly;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        Intent intent = getIntent();
        book = new Book(intent.getStringExtra(EXTRA_ID), intent.getStringExtra(EXTRA_TITLE),
                intent.getStringExtra(EXTRA_DETAIL), intent.getStringExtra(EXTRA_LATEST),
                "", "", intent.getStringExtra(EXTRA_UPDATED));

        titleView = (TextView) findViewById(R.id.picker_title);
        statusView = (TextView) findViewById(R.id.picker_status);
        loading = (ProgressBar) findViewById(R.id.loading);
        list = (ListView) findViewById(R.id.chapter_list);

        titleView.setText(book.title);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        textOnly = (CheckBox) findViewById(R.id.text_only);
        textOnly.setChecked(getSharedPreferences("esjzone_ui", MODE_PRIVATE)
                .getBoolean("textOnly", false));
        textOnly.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                getSharedPreferences("esjzone_ui", MODE_PRIVATE).edit()
                        .putBoolean("textOnly", isChecked).apply();
                updateStatus();
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Row row = adapter.getItem(position);
                if (row.volume) {
                    toggleVolume(row);
                } else {
                    toggle(row.chapter.url);
                }
                adapter.notifyDataSetChanged();
                updateStatus();
            }
        });

        findViewById(R.id.select_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll(true);
            }
        });
        findViewById(R.id.select_none).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll(false);
            }
        });
        findViewById(R.id.download_selected).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSelected();
            }
        });
        findViewById(R.id.download_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAll();
            }
        });

        load();
    }

    // ---------------------------------------------------------------- loading

    private void load() {
        loading.setVisibility(View.VISIBLE);
        statusView.setText(R.string.picker_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                try {
                    CookieStore cookies = new CookieStore(ChapterPickerActivity.this);
                    Fetcher fetcher = new Fetcher(cookies);
                    String html = fetcher.html(book.detailUrl);
                    Document doc = Jsoup.parse(html, book.detailUrl);
                    if (!DetailParser.looksLikeDetail(doc)) {
                        failure = Fetcher.looksSignedOut(html)
                                ? "登录已失效，请重新登录" : "详情页结构异常";
                    } else {
                        detail = DetailParser.parse(doc, book.detailUrl);
                        T2s t2s = T2sHolder.get(ChapterPickerActivity.this);
                        detail.title = t2s.convert(detail.title);
                        convertTitles(detail.outline, t2s);
                    }
                } catch (Exception error) {
                    failure = Fetcher.describe(error);
                }
                final String problem = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.setVisibility(View.GONE);
                        if (problem != null) {
                            statusView.setText(problem);
                            Toast.makeText(ChapterPickerActivity.this, problem,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        buildRows();
                        adapter.notifyDataSetChanged();
                        selectAll(true);
                        updateStatus();
                    }
                });
            }
        }, "picker-load").start();
    }

    private static void convertTitles(ArrayList<Outline> nodes, T2s t2s) {
        for (int i = 0; i < nodes.size(); i++) {
            Outline node = nodes.get(i);
            node.title = t2s.convert(node.title);
            convertTitles(node.children, t2s);
        }
    }

    private void buildRows() {
        rows.clear();
        walk(detail.outline, 0);
    }

    private void walk(ArrayList<Outline> nodes, int level) {
        for (int i = 0; i < nodes.size(); i++) {
            Outline node = nodes.get(i);
            if (node.volume) {
                Row header = new Row(true, node.title, null, level);
                ArrayList<Outline> chapters = new ArrayList<Outline>();
                node.collectChapters(chapters);
                for (int c = 0; c < chapters.size(); c++) {
                    header.urls.add(chapters.get(c).url);
                }
                rows.add(header);
                walk(node.children, level + 1);
            } else {
                rows.add(new Row(false, node.title, node, level));
            }
        }
    }

    private ArrayList<Row> chapterRows() {
        ArrayList<Row> chapters = new ArrayList<Row>();
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).volume) {
                chapters.add(rows.get(i));
            }
        }
        return chapters;
    }

    // ---------------------------------------------------------------- selection

    private void toggle(String url) {
        if (!selected.remove(url)) {
            selected.add(url);
        }
    }

    private void toggleVolume(Row row) {
        boolean allSelected = true;
        for (int i = 0; i < row.urls.size(); i++) {
            if (!selected.contains(row.urls.get(i))) {
                allSelected = false;
                break;
            }
        }
        for (int i = 0; i < row.urls.size(); i++) {
            if (allSelected) {
                selected.remove(row.urls.get(i));
            } else {
                selected.add(row.urls.get(i));
            }
        }
    }

    private void selectAll(boolean checked) {
        selected.clear();
        if (checked) {
            ArrayList<Row> chapters = chapterRows();
            for (int i = 0; i < chapters.size(); i++) {
                selected.add(chapters.get(i).chapter.url);
            }
        }
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void updateStatus() {
        int total = chapterRows().size();
        String suffix = textOnly != null && textOnly.isChecked() ? " · 仅文字" : "";
        statusView.setText("已选 " + selected.size() + " / " + total + " 章" + suffix);
    }

    // ---------------------------------------------------------------- start

    private void startSelected() {
        if (detail == null) {
            return;
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "还没有选中任何章节", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> urls = new ArrayList<String>();
        ArrayList<Row> chapters = chapterRows();
        for (int i = 0; i < chapters.size(); i++) {
            String url = chapters.get(i).chapter.url;
            if (selected.contains(url)) {
                urls.add(url);
            }
        }
        if (urls.size() == chapters.size()) {
            // the whole book: send it as "all chapters" so a resume still covers everything
            urls.clear();
        }
        Downloader.get(this).enqueue(book, urls, textOnly.isChecked());
        goToDownloads();
    }

    private void startAll() {
        Downloader.get(this).enqueue(book, new ArrayList<String>(), textOnly.isChecked());
        goToDownloads();
    }

    private void goToDownloads() {
        startActivity(new Intent(this, DownloadsActivity.class));
        finish();
    }

    // ---------------------------------------------------------------- adapter

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_chapter, parent, false);
            }
            Row item = getItem(position);
            CheckBox check = (CheckBox) row.findViewById(R.id.chapter_check);
            TextView title = (TextView) row.findViewById(R.id.chapter_title);
            Row tag = item;

            title.setText(item.title);
            title.setPadding(item.level * 16, 0, 0, 0);
            if (item.volume) {
                check.setVisibility(View.GONE);
                title.setTextColor(getResources().getColor(R.color.accent));
                title.getPaint().setFakeBoldText(true);
            } else {
                check.setVisibility(View.VISIBLE);
                title.setTextColor(getResources().getColor(R.color.text_primary));
                title.getPaint().setFakeBoldText(false);
                // the checkbox is not clickable, the row handles the toggle
                check.setChecked(selected.contains(tag.chapter.url));
            }
            return row;
        }
    }
}
