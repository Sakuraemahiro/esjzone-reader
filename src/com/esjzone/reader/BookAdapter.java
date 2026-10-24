package com.esjzone.reader;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Renders one favourites row and reflects its live download state. */
public final class BookAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final Downloader downloader;
    private final ArrayList<Book> books = new ArrayList<Book>();
    private final Set<String> finished = new HashSet<String>();

    private final int accent;
    private final int muted;
    private final int ok;
    private final int warn;

    public BookAdapter(Context context, Downloader downloader, Collection<String> finishedKeys) {
        this.inflater = LayoutInflater.from(context);
        this.downloader = downloader;
        this.finished.addAll(finishedKeys);
        this.accent = context.getResources().getColor(R.color.accent);
        this.muted = context.getResources().getColor(R.color.text_muted);
        this.ok = context.getResources().getColor(R.color.ok);
        this.warn = context.getResources().getColor(R.color.warn);
    }

    public void setBooks(ArrayList<Book> list) {
        books.clear();
        books.addAll(list);
        notifyDataSetChanged();
    }

    public void setFinished(String key, boolean isFinished) {
        if (isFinished) {
            finished.add(key);
        } else {
            finished.remove(key);
        }
    }

    public boolean isFinished(String key) {
        return finished.contains(key);
    }

    @Override
    public int getCount() {
        return books.size();
    }

    @Override
    public Book getItem(int position) {
        return books.get(position);
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
            row.setTag(new Holder(row));
        }
        Holder holder = (Holder) row.getTag();
        Book book = getItem(position);
        Downloader.Task task = downloader.taskFor(book);
        boolean done = finished.contains(book.key())
                && (task == null || task.state == Downloader.DONE);

        holder.title.setText(book.title);

        StringBuilder meta = new StringBuilder();
        if (book.latestTitle.length() > 0) {
            meta.append("最新：").append(book.latestTitle);
        }
        if (book.updated.length() > 0) {
            if (meta.length() > 0) {
                meta.append("　");
            }
            meta.append("更新 ").append(book.updated);
        }

        if (done) {
            holder.action.setText("已下载");
            holder.action.setTextColor(ok);
            holder.progress.setVisibility(View.GONE);
        } else if (task == null || task.state == Downloader.FAILED) {
            holder.action.setText(task == null ? "下载" : "重试");
            holder.action.setTextColor(task == null ? accent : warn);
            holder.progress.setVisibility(View.GONE);
            if (task != null && task.message.length() > 0) {
                meta.append("\n").append(task.message);
            }
        } else if (task.state == Downloader.QUEUED) {
            holder.action.setText("排队中");
            holder.action.setTextColor(muted);
            holder.progress.setVisibility(View.GONE);
        } else {
            if (task.state == Downloader.CHAPTERS) {
                holder.action.setText(task.chaptersTotal > 0
                        ? task.chaptersDone + "/" + task.chaptersTotal : "下载中");
            } else if (task.state == Downloader.IMAGES) {
                holder.action.setText("插图 " + task.imagesDone);
            } else if (task.state == Downloader.PACKING) {
                holder.action.setText("打包中");
            } else {
                holder.action.setText("读取目录");
            }
            holder.action.setTextColor(accent);
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setIndeterminate(task.state != Downloader.CHAPTERS);
            holder.progress.setProgress(task.percent());
            if (task.message.length() > 0) {
                meta.append("\n").append(task.message);
            }
        }

        holder.meta.setText(meta);
        return row;
    }

    private static final class Holder {
        final TextView title;
        final TextView meta;
        final TextView action;
        final ProgressBar progress;

        Holder(View row) {
            title = (TextView) row.findViewById(R.id.book_title);
            meta = (TextView) row.findViewById(R.id.book_meta);
            action = (TextView) row.findViewById(R.id.book_action);
            progress = (ProgressBar) row.findViewById(R.id.book_progress);
        }
    }
}
