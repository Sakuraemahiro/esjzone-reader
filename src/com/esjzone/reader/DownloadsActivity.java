package com.esjzone.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * The download queue: everything running or waiting, with live size and illustration counts.
 *
 * <p>The work itself lives in {@link DownloadService}, so this page is only a view onto it.</p>
 */
public class DownloadsActivity extends Activity {

    private Downloader downloader;
    private ListView list;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TaskAdapter adapter;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        downloader = Downloader.get(this);
        list = (ListView) findViewById(R.id.task_list);
        status = (TextView) findViewById(R.id.status);

        adapter = new TaskAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Downloader.Task task = adapter.getItem(position);
                if (task.state == Downloader.DONE) {
                    openLibrary();
                    return;
                }
                if (task.state == Downloader.FAILED) {
                    new AlertDialog.Builder(DownloadsActivity.this)
                            .setTitle(task.title)
                            .setMessage(task.message)
                            .setPositiveButton(R.string.retry, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    downloader.enqueue(task.book, task.selection, task.textOnly);
                                }
                            })
                            .setNegativeButton(R.string.close, null)
                            .show();
                    return;
                }
                new AlertDialog.Builder(DownloadsActivity.this)
                        .setTitle(task.title)
                        .setMessage(task.message)
                        .setPositiveButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                downloader.cancel(task.book);
                            }
                        })
                        .setNegativeButton(R.string.close, null)
                        .show();
            }
        });

        Nav.wire(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void openLibrary() {
        startActivity(new Intent(this, LibraryActivity.class));
    }

    private void refresh() {
        ArrayList<Downloader.Task> tasks = downloader.allTasks();
        adapter.setTasks(tasks);

        int active = 0;
        long bytes = 0;
        for (int i = 0; i < tasks.size(); i++) {
            Downloader.Task task = tasks.get(i);
            if (task.state != Downloader.DONE && task.state != Downloader.FAILED) {
                active++;
            }
            bytes += task.bytesDone;
        }
        StringBuilder text = new StringBuilder();
        text.append(tasks.size()).append(" 个任务");
        if (active > 0) {
            text.append(" · ").append(active).append(" 个进行中");
        }
        text.append(" · 已下载 ").append(Downloader.humanBytes(bytes));
        status.setText(text);

        // keep the screen awake exactly while something is downloading
        if (active > 0) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private final class TaskAdapter extends BaseAdapter {
        private final ArrayList<Downloader.Task> tasks = new ArrayList<Downloader.Task>();
        private final LayoutInflater inflater = LayoutInflater.from(DownloadsActivity.this);

        void setTasks(ArrayList<Downloader.Task> list) {
            tasks.clear();
            tasks.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return tasks.size();
        }

        @Override
        public Downloader.Task getItem(int position) {
            return tasks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = inflater.inflate(R.layout.item_book, parent, false);
            }
            Downloader.Task task = getItem(position);
            TextView title = (TextView) row.findViewById(R.id.book_title);
            TextView meta = (TextView) row.findViewById(R.id.book_meta);
            TextView action = (TextView) row.findViewById(R.id.book_action);
            ProgressBar progress = (ProgressBar) row.findViewById(R.id.book_progress);

            title.setText(task.title.length() > 0 ? task.title : task.book.title);

            StringBuilder line = new StringBuilder();
            line.append(Downloader.humanBytes(task.bytesDone));
            if (task.chaptersTotal > 0) {
                line.append(" · 章节 ").append(Math.min(task.chaptersDone, task.chaptersTotal))
                        .append("/").append(task.chaptersTotal);
            }
            if (task.state == Downloader.CHAPTERS && task.imagesFound > 0) {
                line.append(" · 已发现插图 ").append(task.imagesFound).append(" 张");
            }
            if (task.state == Downloader.IMAGES) {
                line.append(" · 插图 ").append(task.imagesDone).append("/").append(task.imagesTotal);
            }
            if (task.selection.size() > 0) {
                line.append(" · 仅选 ").append(task.selection.size()).append(" 章");
            }
            if (task.state == Downloader.FAILED || task.state == Downloader.DONE) {
                line.append("\n").append(task.message);
            }
            meta.setText(line);

            switch (task.state) {
                case Downloader.DONE:
                    action.setText("已完成");
                    action.setTextColor(getResources().getColor(R.color.ok));
                    progress.setVisibility(View.GONE);
                    break;
                case Downloader.FAILED:
                    action.setText("失败");
                    action.setTextColor(getResources().getColor(R.color.warn));
                    progress.setVisibility(View.GONE);
                    break;
                case Downloader.PACKING:
                    action.setText("打包中");
                    action.setTextColor(getResources().getColor(R.color.accent));
                    progress.setVisibility(View.VISIBLE);
                    progress.setIndeterminate(true);
                    break;
                default:
                    action.setText(task.percent() + "%");
                    action.setTextColor(getResources().getColor(R.color.accent));
                    progress.setVisibility(View.VISIBLE);
                    progress.setIndeterminate(false);
                    progress.setProgress(task.percent());
                    break;
            }
            return row;
        }
    }
}
