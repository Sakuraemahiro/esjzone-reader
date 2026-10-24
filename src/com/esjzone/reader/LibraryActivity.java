package com.esjzone.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Books already downloaded: size, chapter count, and actions on each one. */
public class LibraryActivity extends Activity {

    private LibraryStore library;
    private ListView list;
    private TextView status;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        library = new LibraryStore(this);
        list = (ListView) findViewById(R.id.book_list);
        status = (TextView) findViewById(R.id.status);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showActions(adapter.getItem(position));
            }
        });

        Nav.wire(this);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        ArrayList<LibraryStore.Item> items = library.all();
        adapter.setItems(items);
        long total = 0;
        for (int i = 0; i < items.size(); i++) {
            total += items.get(i).bytes;
        }
        status.setText(items.size() + " 本 · 共 " + Downloader.humanBytes(total));
    }

    private void showActions(final LibraryStore.Item item) {
        final String[] actions = {getString(R.string.open), "查看文件位置", getString(R.string.delete)};
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            open(item);
                        } else if (which == 1) {
                            new AlertDialog.Builder(LibraryActivity.this)
                                    .setTitle("文件位置")
                                    .setMessage(item.location.length() > 0
                                            ? item.location : "下载/ESJZone小说/" + item.title + ".epub")
                                    .setPositiveButton(R.string.close, null)
                                    .show();
                        } else {
                            library.remove(item.bookId);
                            reload();
                            Toast.makeText(LibraryActivity.this,
                                    "已从列表移除（文件仍保留在下载目录）", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void open(LibraryStore.Item item) {
        if (item.uri == null || item.uri.length() == 0) {
            Toast.makeText(this, "文件在「下载/" + Storage.FOLDER + "」，请用文件管理器打开",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(item.uri), Storage.MIME_EPUB);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "没有可以打开 EPUB 的应用", Toast.LENGTH_LONG).show();
        }
    }

    private final class Adapter extends BaseAdapter {
        private final ArrayList<LibraryStore.Item> items = new ArrayList<LibraryStore.Item>();
        private final SimpleDateFormat date =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

        void setItems(ArrayList<LibraryStore.Item> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public LibraryStore.Item getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_book, parent, false);
            }
            LibraryStore.Item item = getItem(position);
            TextView title = (TextView) row.findViewById(R.id.book_title);
            TextView meta = (TextView) row.findViewById(R.id.book_meta);
            TextView action = (TextView) row.findViewById(R.id.book_action);
            View progress = row.findViewById(R.id.book_progress);
            progress.setVisibility(View.GONE);

            title.setText(item.title);
            StringBuilder line = new StringBuilder();
            line.append(item.sizeText());
            if (item.chapters > 0) {
                line.append(" · ").append(item.chapters).append(" 章");
            }
            if (item.images > 0) {
                line.append(" · ").append(item.images).append(" 张图");
            }
            if (item.finishedAt > 0) {
                line.append("\n").append(date.format(new Date(item.finishedAt)));
            }
            if (item.author.length() > 0) {
                line.append(" · ").append(item.author);
            }
            meta.setText(line);

            action.setText("打开");
            action.setTextColor(getResources().getColor(R.color.accent));
            return row;
        }
    }
}
