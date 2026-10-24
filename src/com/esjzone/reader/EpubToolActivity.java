package com.esjzone.reader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * One screen for both offline tools.
 *
 * <p>Split mode lists the chapters of the chosen EPUB with the size of each one (its document
 * plus the pictures it references) and exports them either as one file per chapter or merged
 * into a single book. Compress mode re-encodes the pictures of one EPUB and reports the saving.
 * Nothing here opens a socket.</p>
 */
public class EpubToolActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_SPLIT = "split";
    public static final String MODE_COMPRESS = "compress";

    private static final int REQUEST_PICK = 100;
    private static final int MAX_EDGE = 1600;
    private static final int JPEG_QUALITY = 82;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HashSet<Integer> selected = new HashSet<Integer>();

    private String mode;
    private File working;
    private String displayName = "";
    private EpubFile.Book book;
    private int exportMode = SplitPlan.EACH;

    private TextView status;
    private ListView list;
    private TextView empty;
    private ProgressBar progress;
    private Button go;
    private Button allButton;
    private Button noneButton;
    private Button modeOne;
    private Button modeGroup;
    private Button modeMerge;
    private View groupRow;
    private EditText groupSize;
    private ChapterAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_epub_tool);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) {
            mode = MODE_SPLIT;
        }
        boolean split = MODE_SPLIT.equals(mode);

        TextView title = (TextView) findViewById(R.id.tool_title);
        status = (TextView) findViewById(R.id.tool_status);
        list = (ListView) findViewById(R.id.tool_list);
        empty = (TextView) findViewById(R.id.tool_empty);
        progress = (ProgressBar) findViewById(R.id.tool_progress);
        go = (Button) findViewById(R.id.tool_go);
        allButton = (Button) findViewById(R.id.tool_all);
        noneButton = (Button) findViewById(R.id.tool_none);
        modeOne = (Button) findViewById(R.id.tool_mode_one);
        modeGroup = (Button) findViewById(R.id.tool_mode_group);
        modeMerge = (Button) findViewById(R.id.tool_mode_merge);
        groupRow = findViewById(R.id.tool_group_row);
        groupSize = (EditText) findViewById(R.id.tool_group_size);

        title.setText(split ? R.string.split_title : R.string.compress_title);
        go.setText(split ? R.string.split_export : R.string.compress_start);

        int modeVisibility = split ? View.VISIBLE : View.GONE;
        allButton.setVisibility(modeVisibility);
        noneButton.setVisibility(modeVisibility);
        modeOne.setVisibility(modeVisibility);
        modeGroup.setVisibility(modeVisibility);
        modeMerge.setVisibility(modeVisibility);
        list.setVisibility(split ? View.VISIBLE : View.GONE);
        empty.setVisibility(split ? View.VISIBLE : View.GONE);

        adapter = new ChapterAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (selected.remove(position)) {
                    // already selected
                } else {
                    selected.add(position);
                }
                adapter.notifyDataSetChanged();
                updateStatus();
            }
        });

        findViewById(R.id.tool_pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        allButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll(true);
            }
        });
        noneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll(false);
            }
        });
        modeOne.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportMode = SplitPlan.EACH;
                updateModeButtons();
            }
        });
        modeGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportMode = SplitPlan.GROUP;
                updateModeButtons();
            }
        });
        modeMerge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportMode = SplitPlan.MERGE;
                updateModeButtons();
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MODE_SPLIT.equals(mode)) {
                    exportSelected();
                } else {
                    compress();
                }
            }
        });

        updateModeButtons();
    }

    private void updateModeButtons() {
        modeOne.setSelected(exportMode == SplitPlan.EACH);
        modeGroup.setSelected(exportMode == SplitPlan.GROUP);
        modeMerge.setSelected(exportMode == SplitPlan.MERGE);
        // the "how many chapters per file" box only matters in group mode
        groupRow.setVisibility(exportMode == SplitPlan.GROUP ? View.VISIBLE : View.GONE);
        if (MODE_SPLIT.equals(mode)) {
            go.setText(exportMode == SplitPlan.GROUP
                    ? R.string.split_group_export : R.string.split_export);
        }
    }

    /** Chapters per file in group mode, clamped to something sane. */
    private int groupCount() {
        return SplitPlan.parseGroupSize(groupSize.getText().toString());
    }

    // ---------------------------------------------------------------- picking

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/epub+zip", "application/octet-stream", "application/zip"});
        try {
            startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception error) {
            toast("设备上没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        displayName = queryDisplayName(uri);
        progress.setVisibility(View.VISIBLE);
        status.setText("正在读取文件…");
        final Uri source = uri;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                try {
                    File dir = new File(getCacheDir(), "tools");
                    dir.mkdirs();
                    working = new File(dir, "source.epub");
                    InputStream in = getContentResolver().openInputStream(source);
                    if (in == null) {
                        throw new java.io.IOException("无法读取所选文件");
                    }
                    try {
                        FileOutputStream out = new FileOutputStream(working);
                        try {
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                out.write(buffer, 0, read);
                            }
                        } finally {
                            out.close();
                        }
                    } finally {
                        in.close();
                    }
                    if (MODE_SPLIT.equals(mode)) {
                        book = EpubFile.read(working);
                        if (book.chapters.isEmpty()) {
                            failure = "这个 EPUB 里没有可拆分的章节";
                        }
                    }
                } catch (Exception error) {
                    failure = "读取失败：" + error.getMessage();
                }
                final String problem = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisibility(View.GONE);
                        if (problem != null) {
                            status.setText(problem);
                            toast(problem);
                            return;
                        }
                        onFileReady();
                    }
                });
            }
        }, "tool-read").start();
    }

    private void onFileReady() {
        if (MODE_SPLIT.equals(mode)) {
            selected.clear();
            for (int i = 0; i < book.chapters.size(); i++) {
                selected.add(i);
            }
            adapter.notifyDataSetChanged();
            empty.setVisibility(book.chapters.isEmpty() ? View.VISIBLE : View.GONE);
            updateStatus();
        } else {
            status.setText(working.getName() + " · " + Downloader.humanBytes(working.length())
                    + "\n点「" + getString(R.string.compress_start) + "」开始压缩插图");
        }
    }

    private void selectAll(boolean checked) {
        selected.clear();
        if (checked && book != null) {
            for (int i = 0; i < book.chapters.size(); i++) {
                selected.add(i);
            }
        }
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void updateStatus() {
        if (book == null) {
            return;
        }
        long bytes = 0;
        for (Integer index : selected) {
            bytes += book.chapters.get(index).bytes;
        }
        status.setText(String.format(Locale.US, "%s · 已选 %d / %d 章 · %s",
                book.title.length() > 0 ? book.title : working.getName(),
                selected.size(), book.chapters.size(), Downloader.humanBytes(bytes)));
    }

    // ---------------------------------------------------------------- split

    private void exportSelected() {
        if (book == null) {
            toast("请先选择一个 EPUB 文件");
            return;
        }
        if (selected.isEmpty()) {
            toast("还没有选中任何章节");
            return;
        }
        final ArrayList<EpubFile.Chapter> chapters = new ArrayList<EpubFile.Chapter>();
        final ArrayList<Integer> positions = new ArrayList<Integer>();
        for (int i = 0; i < book.chapters.size(); i++) {
            if (selected.contains(i)) {
                chapters.add(book.chapters.get(i));
                positions.add(i);
            }
        }
        final int chosenMode = exportMode;
        final int perFile = groupCount();
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.working);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int written = 0;
                String failure = null;
                try {
                    String base = SplitPlan.sanitize(book.title.length() > 0 ? book.title
                            : nameWithoutExtension());
                    final ArrayList<SplitPlan.Job> jobs = SplitPlan.plan(base, chapters, positions,
                            chosenMode, perFile);
                    for (int j = 0; j < jobs.size(); j++) {
                        final SplitPlan.Job job = jobs.get(j);
                        final int done = j + 1;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("正在导出 " + done + " / " + jobs.size()
                                        + "：" + job.fileName);
                            }
                        });
                        Storage.Sink sink = Storage.open(EpubToolActivity.this, job.fileName,
                                Storage.MIME_EPUB);
                        try {
                            OutputStream out = sink.stream();
                            try {
                                EpubFile.export(working, book, job.chapters, job.title, out);
                            } finally {
                                out.close();
                            }
                            sink.finish();
                            written++;
                        } catch (Exception error) {
                            sink.abort();
                        }
                    }
                } catch (Exception error) {
                    failure = "导出失败：" + error.getMessage();
                }
                final int count = written;
                final String problem = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisibility(View.GONE);
                        if (problem != null) {
                            status.setText(problem);
                            toast(problem);
                            return;
                        }
                        String message = "已导出 " + count + " 个文件到「下载/"
                                + Storage.FOLDER + "」";
                        status.setText(message);
                        toast(message);
                    }
                });
            }
        }, "tool-split").start();
    }

    // ---------------------------------------------------------------- compress

    private void compress() {
        if (working == null) {
            toast("请先选择一个 EPUB 文件");
            return;
        }
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.working);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                String summary = "";
                try {
                    File temp = new File(getCacheDir(), "tools/compressed.epub");
                    FileOutputStream fileOut = new FileOutputStream(temp);
                    ImageCompressor.Result result;
                    try {
                        result = ImageCompressor.compress(working, fileOut, MAX_EDGE, JPEG_QUALITY);
                    } finally {
                        fileOut.close();
                    }
                    result.after = temp.length();

                    String base = safeName(book != null && book.title.length() > 0
                            ? book.title : nameWithoutExtension());                    Storage.Sink sink = Storage.open(EpubToolActivity.this,
                            base + " - 压缩.epub", Storage.MIME_EPUB);
                    try {
                        OutputStream out = sink.stream();
                        try {
                            InputStream in = new java.io.FileInputStream(temp);
                            try {
                                byte[] buffer = new byte[64 * 1024];
                                int read;
                                while ((read = in.read(buffer)) > 0) {
                                    out.write(buffer, 0, read);
                                }
                            } finally {
                                in.close();
                            }
                        } finally {
                            out.close();
                        }
                        sink.finish();
                    } catch (Exception error) {
                        sink.abort();
                        throw error;
                    }
                    temp.delete();
                    summary = result.summary();
                } catch (Exception error) {
                    failure = "压缩失败：" + error.getMessage();
                }
                final String problem = failure;
                final String text = summary;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisibility(View.GONE);
                        if (problem != null) {
                            status.setText(problem);
                            toast(problem);
                            return;
                        }
                        status.setText(text);
                        toast("压缩完成");
                    }
                });
            }
        }, "tool-compress").start();
    }

    // ---------------------------------------------------------------- helpers

    private String nameWithoutExtension() {
        String name = displayName.length() > 0 ? displayName
                : (working == null ? "book" : working.getName());
        return name.replaceAll("(?i)\\.epub$", "");
    }

    /** The picker gives us a cache copy; the original name is what the user recognises. */
    private String queryDisplayName(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && name.length() > 0) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to the cache file name
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "";
    }

    private static String safeName(String text) {
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (working != null) {
            working.delete();
        }
    }

    // ---------------------------------------------------------------- adapter

    private final class ChapterAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return book == null ? 0 : book.chapters.size();
        }

        @Override
        public EpubFile.Chapter getItem(int position) {
            return book.chapters.get(position);
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
            EpubFile.Chapter chapter = getItem(position);
            CheckBox check = (CheckBox) row.findViewById(R.id.chapter_check);
            TextView title = (TextView) row.findViewById(R.id.chapter_title);
            check.setVisibility(View.VISIBLE);
            check.setChecked(selected.contains(position));
            title.setPadding(0, 0, 0, 0);
            title.setText(chapter.title + "　·　" + Downloader.humanBytes(chapter.bytes)
                    + (chapter.images > 0 ? "（含 " + chapter.images + " 图）" : ""));
            return row;
        }
    }
}
