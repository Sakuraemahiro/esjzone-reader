package com.esjzone.reader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/** The favourites list: loads /my/favorite, then hands books to the downloader. */
public class MainActivity extends Activity
        implements Downloader.Listener, AdapterView.OnItemClickListener {

    private static final int REQUEST_LOGIN = 1;
    private static final int REQUEST_STORAGE = 1001;
    private static final int MAX_PAGES = 12;

    private static final String PREFS = "esjzone_library";
    private static final String KEY_DONE = "finished";
    private static final String KEY_URI_PREFIX = "uri:";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Book> allBooks = new ArrayList<Book>();
    private final ArrayList<Book> visible = new ArrayList<Book>();
    private final Set<String> finished = new HashSet<String>();

    private SharedPreferences prefs;
    private CookieStore cookies;
    private Fetcher fetcher;
    private Downloader downloader;
    private BookAdapter adapter;

    private EditText search;
    private TextView status;
    private TextView user;
    private TextView emptyText;
    private Button emptyAction;
    private Button sortNew;
    private Button sortUpdate;

    private String sort = "new";
    private boolean loading;
    private Book pendingBook;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        finished.addAll(prefs.getStringSet(KEY_DONE, new HashSet<String>()));
        cookies = new CookieStore(this);
        fetcher = new Fetcher(cookies);
        downloader = Downloader.get(this);
        downloader.setListener(this);

        search = (EditText) findViewById(R.id.search);
        status = (TextView) findViewById(R.id.status);
        user = (TextView) findViewById(R.id.user);
        emptyText = (TextView) findViewById(R.id.empty_text);
        emptyAction = (Button) findViewById(R.id.empty_action);
        sortNew = (Button) findViewById(R.id.sort_new);
        sortUpdate = (Button) findViewById(R.id.sort_update);
        ListView list = (ListView) findViewById(R.id.book_list);

        adapter = new BookAdapter(this, downloader, finished);
        list.setAdapter(adapter);
        list.setOnItemClickListener(this);
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showBookMenu(adapter.getItem(position));
                return true;
            }
        });
        list.setEmptyView(findViewById(R.id.empty));

        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFavorites();
            }
        });
        sortNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSort("new");
            }
        });
        sortUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSort("udate");
            }
        });
        emptyAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cookies.hasSession()) {
                    loadFavorites();
                } else {
                    startLogin();
                }
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });

        Nav.wire(this);
        updateUserLabel();
        if (cookies.hasSession()) {
            loadFavorites();
        } else {
            showEmptyState(getString(R.string.login_needed), getString(R.string.login_now));
            startLogin();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        downloader.setListener(this);
        if (!downloader.activeTasks().isEmpty()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------------- account

    private void startLogin() {
        startActivityForResult(new Intent(this, LoginActivity.class), REQUEST_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOGIN) {
            return;
        }
        updateUserLabel();
        if (resultCode == RESULT_OK && cookies.hasSession()) {
            loadFavorites();
        } else if (!cookies.hasSession()) {
            showEmptyState(getString(R.string.login_needed), getString(R.string.login_now));
        }
    }

    private void updateUserLabel() {
        String name = cookies.userName();
        user.setText(cookies.hasSession()
                ? (name.length() > 0 ? name : getString(R.string.subtitle))
                : getString(R.string.guest));
    }

    private void showEmptyState(String text, String action) {
        emptyText.setText(text);
        emptyAction.setText(action);
        emptyAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- list

    private void setSort(String value) {
        if (sort.equals(value)) {
            return;
        }
        sort = value;
        sortNew.setSelected("new".equals(value));
        sortUpdate.setSelected("udate".equals(value));
        loadFavorites();
    }

    private void loadFavorites() {
        if (loading) {
            return;
        }
        if (!cookies.hasSession()) {
            showEmptyState(getString(R.string.login_needed), getString(R.string.login_now));
            startLogin();
            return;
        }
        loading = true;
        status.setText("正在读取收藏夹…");
        sortNew.setSelected("new".equals(sort));
        sortUpdate.setSelected("udate".equals(sort));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final LinkedHashMap<String, Book> merged = new LinkedHashMap<String, Book>();
                String failure = null;
                try {
                    String first = Site.favorites(sort, 1);
                    String html = fetcher.html(first);
                    if (Fetcher.looksSignedOut(html)) {
                        failure = "登录已失效，请重新登录";
                    } else {
                        int pages = Math.min(FavoritesParser.pageCount(html), MAX_PAGES);
                        collect(merged, FavoritesParser.parse(Jsoup.parse(html, first), first));
                        for (int page = 2; page <= pages; page++) {
                            String url = Site.favorites(sort, page);
                            String more = fetcher.html(url);
                            collect(merged, FavoritesParser.parse(Jsoup.parse(more, url), url));
                        }
                    }
                } catch (Exception error) {
                    failure = Fetcher.describe(error);
                }

                final String problem = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (problem != null) {
                            status.setText(problem);
                            if (allBooks.isEmpty()) {
                                showEmptyState(problem, cookies.hasSession()
                                        ? getString(R.string.refresh) : getString(R.string.login_now));
                            }
                            return;
                        }
                        allBooks.clear();
                        allBooks.addAll(merged.values());
                        applyFilter();
                    }
                });
            }
        }, "favorites").start();
    }

    private static void collect(LinkedHashMap<String, Book> target, ArrayList<Book> page) {
        for (int i = 0; i < page.size(); i++) {
            Book book = page.get(i);
            if (!target.containsKey(book.id)) {
                target.put(book.id, book);
            }
        }
    }

    private void applyFilter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (int i = 0; i < allBooks.size(); i++) {
            Book book = allBooks.get(i);
            if (query.length() == 0 || book.title.toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(book);
            }
        }
        adapter.setBooks(visible);

        int done = 0;
        for (int i = 0; i < allBooks.size(); i++) {
            if (finished.contains(allBooks.get(i).key())) {
                done++;
            }
        }
        int running = downloader.activeTasks().size();
        StringBuilder text = new StringBuilder();
        text.append("收藏 ").append(allBooks.size()).append(" 本 · 已下载 ").append(done).append(" 本");
        if (query.length() > 0) {
            text.append(" · 匹配 ").append(visible.size());
        }
        if (running > 0) {
            text.append(" · ").append(running).append(" 个任务进行中");
        }
        status.setText(text);
        if (visible.isEmpty() && allBooks.isEmpty() && !loading) {
            showEmptyState(getString(R.string.empty), cookies.hasSession()
                    ? getString(R.string.refresh) : getString(R.string.login_now));
        }
    }

    // ---------------------------------------------------------------- actions

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Book book = adapter.getItem(position);
        Downloader.Task task = downloader.taskFor(book);
        if (adapter.isFinished(book.key())
                && (task == null || task.state == Downloader.DONE)) {
            showFinishedDialog(book);
            return;
        }
        if (task != null && task.state != Downloader.DONE && task.state != Downloader.FAILED) {
            downloader.cancel(book);
            toast("已请求取消：" + book.title);
            return;
        }
        startDownload(book);
    }

    private void showFinishedDialog(final Book book) {
        final String uri = prefs.getString(KEY_URI_PREFIX + book.key(), null);
        new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setMessage("已保存到「下载/" + Storage.FOLDER + "」")
                .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openBook(uri);
                    }
                })
                .setNeutralButton("重新下载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finished.remove(book.key());
                        adapter.setFinished(book.key(), false);
                        persistFinished();
                        startDownload(book);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showBookMenu(final Book book) {
        new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setItems(new CharSequence[]{"打开最新章节", "复制详情页链接", "取消"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0 && book.latestUrl.length() > 0) {
                                    openUrl(book.latestUrl);
                                } else if (which == 1) {
                                    ClipboardManager clipboard = (ClipboardManager)
                                            getSystemService(Context.CLIPBOARD_SERVICE);
                                    clipboard.setPrimaryClip(
                                            ClipData.newPlainText("esjzone", book.detailUrl));
                                    toast("链接已复制");
                                }
                            }
                        })
                .show();
    }

    private void startDownload(Book book) {
        // Runtime storage permission exists from API 23; 21-22 grant it at install time and
        // 29+ writes through MediaStore without any permission.
        boolean needsRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
        if (needsRequest) {
            pendingBook = book;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            return;
        }
        openPicker(book);
    }

    /** Tap opens the chapter picker; the picker offers 全部下载 to skip the selection. */
    private void openPicker(Book book) {
        Intent intent = new Intent(this, ChapterPickerActivity.class);
        intent.putExtra(ChapterPickerActivity.EXTRA_ID, book.id);
        intent.putExtra(ChapterPickerActivity.EXTRA_TITLE, book.title);
        intent.putExtra(ChapterPickerActivity.EXTRA_DETAIL, book.detailUrl);
        intent.putExtra(ChapterPickerActivity.EXTRA_LATEST, book.latestTitle);
        intent.putExtra(ChapterPickerActivity.EXTRA_UPDATED, book.updated);
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingBook != null) {
                openPicker(pendingBook);
                pendingBook = null;
            }
        } else {
            pendingBook = null;
            toast("没有存储权限，无法写入下载目录");
        }
    }

    private void openBook(String uri) {
        if (uri == null) {
            toast("文件在「下载/" + Storage.FOLDER + "」，请用文件管理器打开");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(uri), Storage.MIME_EPUB);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception error) {
            toast("没有可以打开 EPUB 的应用");
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Site.canonical(url))));
        } catch (Exception error) {
            toast("没有可以打开网页的应用");
        }
    }

    // ---------------------------------------------------------------- callbacks

    @Override
    public void onTaskChanged(Downloader.Task task) {
        if (task.state == Downloader.DONE) {
            finished.add(task.book.key());
            adapter.setFinished(task.book.key(), true);
            if (task.uri != null) {
                prefs.edit().putString(KEY_URI_PREFIX + task.book.key(), task.uri).apply();
            }
            persistFinished();
            toast(task.title + "：" + task.message);
        } else if (task.state == Downloader.FAILED) {
            finished.remove(task.book.key());
            adapter.setFinished(task.book.key(), false);
            persistFinished();
            toast(task.title + "：" + task.message);
        }
        adapter.notifyDataSetChanged();
    }

    private void persistFinished() {
        prefs.edit().putStringSet(KEY_DONE, new HashSet<String>(finished)).apply();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloader.setListener(null);
    }
}
