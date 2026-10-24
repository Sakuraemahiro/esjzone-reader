package com.esjzone.reader;

import android.content.Context;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads a book: detail page, selected chapters and illustrations, then one EPUB.
 *
 * <p>The work is driven by {@link DownloadService}, so it keeps running while the UI is gone.
 * Queued jobs are persisted through {@link QueueStore}, which is what makes an interrupted
 * download resumable after the process dies.</p>
 */
public final class Downloader {

    private static final String TAG = "ESJ";

    public static final int QUEUED = 0;
    public static final int DETAIL = 1;
    public static final int CHAPTERS = 2;
    public static final int IMAGES = 3;
    public static final int PACKING = 4;
    public static final int DONE = 5;
    public static final int FAILED = 6;

    private static final int CHAPTER_THREADS = 6;
    private static final int IMAGE_THREADS = 5;
    private static final int IMAGE_CONNECT_TIMEOUT = 8000;
    private static final int IMAGE_READ_TIMEOUT = 20000;
    private static final int IMAGE_FAILURE_LIMIT = 3;

    public static final class Task {
        public final Book book;
        /** Chapters the user picked; empty means every chapter. */
        public final ArrayList<String> selection = new ArrayList<String>();
        /** True to keep only the prose: no illustrations, no cover. */
        public final boolean textOnly;
        public volatile int state = QUEUED;
        public volatile int chaptersDone;
        public volatile int chaptersTotal;
        public volatile long bytesDone;
        /** Illustrations discovered so far, while the chapters are still being parsed. */
        public volatile int imagesFound;
        public volatile int imagesTotal;
        public volatile int imagesDone;
        public volatile int imagesFailed;
        public volatile String message = "";
        public volatile String uri;
        public volatile String location = "";
        public volatile String title = "";
        public volatile boolean cancelled;
        public volatile BookDetail detail;

        Task(Book book, ArrayList<String> chapterUrls, boolean textOnly) {
            this.book = book;
            this.textOnly = textOnly;
            if (chapterUrls != null) {
                this.selection.addAll(chapterUrls);
            }
            this.title = book.title;
        }

        public int percent() {
            if (state == DONE) {
                return 100;
            }
            if (state == PACKING) {
                return 97;
            }
            if (state == IMAGES && imagesTotal > 0) {
                return 60 + (int) (imagesDone * 36L / imagesTotal);
            }
            if (chaptersTotal > 0) {
                return 5 + (int) (Math.min(chaptersDone, chaptersTotal) * 55L / chaptersTotal);
            }
            return state == QUEUED ? 0 : 3;
        }
    }

    public interface Listener {
        void onTaskChanged(Task task);
    }

    private final Context appContext;
    private final Fetcher fetcher;
    private final T2s t2s;
    private final QueueStore queue;
    private final File imageDir;
    private final ExecutorService chapters = Executors.newFixedThreadPool(CHAPTER_THREADS);
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<String, Task>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile long lastNotify;

    private static volatile Downloader instance;

    public static Downloader get(Context context) {
        Downloader local = instance;
        if (local == null) {
            synchronized (Downloader.class) {
                local = instance;
                if (local == null) {
                    local = new Downloader(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    private Downloader(Context context) {
        this.appContext = context.getApplicationContext();
        this.fetcher = new Fetcher(new CookieStore(this.appContext));
        this.t2s = T2sHolder.get(this.appContext);
        this.queue = new QueueStore(this.appContext);
        this.imageDir = new File(this.appContext.getCacheDir(), "pictures");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Task taskFor(Book book) {
        return tasks.get(book.key());
    }

    public ArrayList<Task> activeTasks() {
        ArrayList<Task> active = new ArrayList<Task>();
        for (Task task : tasks.values()) {
            if (task.state != DONE && task.state != FAILED) {
                active.add(task);
            }
        }
        return active;
    }

    public ArrayList<Task> allTasks() {
        return new ArrayList<Task>(tasks.values());
    }

    public Task enqueue(Book book) {
        return enqueue(book, new ArrayList<String>(), false);
    }

    public Task enqueue(Book book, ArrayList<String> chapterUrls) {
        return enqueue(book, chapterUrls, false);
    }

    public Task enqueue(Book book, ArrayList<String> chapterUrls, boolean textOnly) {
        Task existing = tasks.get(book.key());
        if (existing != null && existing.state != DONE && existing.state != FAILED) {
            return existing;
        }
        final Task task = new Task(book, chapterUrls, textOnly);
        tasks.put(book.key(), task);

        QueueStore.Job job = new QueueStore.Job();
        job.bookId = book.id;
        job.title = book.title;
        job.detailUrl = book.detailUrl;
        job.latestTitle = book.latestTitle;
        job.updated = book.updated;
        job.requestedAt = System.currentTimeMillis();
        job.textOnly = textOnly;
        if (chapterUrls != null) {
            job.chapterUrls.addAll(chapterUrls);
        }
        queue.put(job);

        notifyNow(task, true);
        DownloadService.start(appContext);
        new Thread(new Runnable() {
            @Override
            public void run() {
                execute(task);
            }
        }, "book-" + book.id).start();
        return task;
    }

    public void cancel(Book book) {
        Task task = tasks.get(book.key());
        if (task != null) {
            task.cancelled = true;
        }
    }

    // ---------------------------------------------------------------- pipeline

    private void execute(Task task) {
        Storage.Sink sink = null;
        try {
            checkCancelled(task);

            task.state = DETAIL;
            task.message = "正在读取目录…";
            notifyNow(task, true);

            Fetcher.Response detailResponse = fetcher.fetch(task.book.detailUrl);
            addBytes(task, detailResponse.length());
            String detailHtml = detailResponse.text();
            Document detailDoc = Jsoup.parse(detailHtml, task.book.detailUrl);
            if (!DetailParser.looksLikeDetail(detailDoc)) {
                throw new IOException(Fetcher.looksSignedOut(detailHtml)
                        ? "登录已失效，请重新登录账号"
                        : "详情页结构异常，站点可能已改版");
            }
            BookDetail detail = DetailParser.parse(detailDoc, task.book.detailUrl);
            if (detail.id.length() == 0) {
                detail.id = task.book.id;
            }
            convert(detail);

            ArrayList<Outline> allChapters = new ArrayList<Outline>();
            collectChapters(detail.outline, allChapters);
            ArrayList<Outline> wanted = filter(allChapters, task.selection);
            if (wanted.isEmpty()) {
                throw new IOException("没有可下载的章节");
            }
            task.detail = detail;
            task.title = detail.title.length() > 0 ? detail.title : task.book.title;
            task.chaptersTotal = wanted.size();
            task.message = "共 " + wanted.size() + " 章，开始下载…";
            notifyNow(task, true);

            task.state = CHAPTERS;
            final ConcurrentHashMap<String, Chapter> contents =
                    new ConcurrentHashMap<String, Chapter>();
            final AtomicInteger done = new AtomicInteger(0);
            final HashSet<String> seenImages = new HashSet<String>();
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int i = 0; i < wanted.size(); i++) {
                final Outline chapter = wanted.get(i);
                futures.add(this.chapters.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (task.cancelled) {
                            return;
                        }
                        Chapter parsed = fetchChapter(chapter, task);
                        contents.put(chapter.url, parsed);
                        synchronized (seenImages) {
                            for (int b = 0; b < parsed.blocks.size(); b++) {
                                Chapter.Block block = parsed.blocks.get(b);
                                if (block.image && block.value.length() > 0) {
                                    seenImages.add(block.value);
                                }
                            }
                            task.imagesFound = seenImages.size();
                        }
                        task.chaptersDone = done.incrementAndGet();
                        notifyNow(task, false);
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (Exception ignored) {
                    // recorded as a placeholder chapter
                }
            }
            checkCancelled(task);

            if (task.textOnly) {
                // dropping the blocks keeps the packed HTML free of dead image links
                dropImageBlocks(contents);
                seenImages.clear();
            }

            task.state = IMAGES;
            task.imagesTotal = task.textOnly ? 0
                    : seenImages.size() + (detail.coverUrl.length() > 0 ? 1 : 0);
            task.message = task.textOnly ? "仅文字模式，跳过插图…"
                    : "插图 " + task.imagesTotal + " 张…";
            notifyNow(task, true);

            LinkedHashMap<String, File> images = new LinkedHashMap<String, File>();
            HashMap<String, String> urlToName = new HashMap<String, String>();
            String coverName = "";
            if (!task.textOnly) {
                coverName = stageImages(detail, wanted, contents, images, urlToName, task);
                rewriteImageReferences(contents, urlToName);
            }

            checkCancelled(task);
            task.state = PACKING;
            task.message = "正在打包 EPUB…";
            notifyNow(task, true);

            EpubBuilder.Meta meta = new EpubBuilder.Meta();
            meta.title = detail.title;
            meta.author = detail.author;
            meta.genre = detail.genre;
            meta.updated = detail.updated;
            meta.description = detail.description;
            meta.sourceUrl = task.book.detailUrl;
            meta.coverFile = coverName;

            ArrayList<Outline> pruned = prune(detail.outline, wanted);
            sink = Storage.open(appContext, detail.fileName(), Storage.MIME_EPUB);
            OutputStream out = sink.stream();
            EpubBuilder.Result result;
            try {
                result = EpubBuilder.write(out, meta, pruned,
                        new HashMap<String, Chapter>(contents), images);
            } finally {
                closeQuietly(out);
            }
            sink.finish();

            task.state = DONE;
            task.uri = sink.uri;
            task.location = sink.location;
            task.message = "完成：" + result.chapters + " 章，插图 " + result.images + " 张"
                    + (task.imagesFailed > 0 ? "（" + task.imagesFailed + " 张失败）" : "")
                    + (task.textOnly ? "（仅文字）" : "")
                    + (result.placeholders > 0 ? "（" + result.placeholders + " 章缺失）" : "");
            queue.remove(task.book.id);
            recordLibrary(task, detail, result, sink);
        } catch (Exception error) {
            if (sink != null) {
                sink.abort();
            }
            task.state = FAILED;
            task.message = task.cancelled ? "已取消" : Fetcher.describe(error);
            queue.remove(task.book.id);
        } finally {
            cleanupPictures(task);
            notifyNow(task, true);
        }
    }

    /** Text-only jobs keep the prose and throw the illustrations away. */
    private static void dropImageBlocks(ConcurrentHashMap<String, Chapter> contents) {
        for (Chapter chapter : contents.values()) {
            for (int i = chapter.blocks.size() - 1; i >= 0; i--) {
                if (chapter.blocks.get(i).image) {
                    chapter.blocks.remove(i);
                }
            }
        }
    }

    private Chapter fetchChapter(Outline chapter, Task task) {
        try {
            Fetcher.Response response = fetcher.fetch(chapter.url);
            addBytes(task, response.length());
            String html = response.text();
            Document doc = Jsoup.parse(html, chapter.url);
            Chapter parsed = ChapterParser.parse(doc, chapter.url);
            if (parsed.unavailable && ChapterParser.isNoticePage(doc)) {
                String report = ChapterParser.reportUrl(chapter.url);
                try {
                    Fetcher.Response recovered = fetcher.fetch(report);
                    addBytes(task, recovered.length());
                    Chapter retry = ChapterParser.parseReport(
                            Jsoup.parse(recovered.text(), report));
                    if (!retry.unavailable) {
                        retry.title = parsed.title;
                        convert(retry);
                        return retry;
                    }
                } catch (Exception ignored) {
                    // fall through to the placeholder
                }
            }
            if (parsed.title.length() == 0) {
                parsed.title = chapter.title;
            }
            convert(parsed);
            return parsed;
        } catch (Exception error) {
            Chapter failed = new Chapter();
            failed.title = chapter.title;
            failed.unavailable = true;
            failed.note = "本章节下载失败：" + Fetcher.describe(error);
            return failed;
        }
    }

    /**
     * Downloads every illustration.
     *
     * <p>This used to walk the chapters one image at a time, which made image-heavy books crawl.
     * The URLs are collected first, fetched by a small pool, and only then folded into the
     * ordered map the packer walks -- so the archive layout stays deterministic.</p>
     */
    private String stageImages(BookDetail detail, ArrayList<Outline> chapters,
                              ConcurrentHashMap<String, Chapter> contents,
                              LinkedHashMap<String, File> images,
                              HashMap<String, String> urlToName, Task task) {
        File dir = new File(imageDir, task.book.id);
        deleteRecursively(dir);
        dir.mkdirs();

        final boolean hasCover = detail.coverUrl.length() > 0;
        final ArrayList<String> urls = new ArrayList<String>();
        HashSet<String> seen = new HashSet<String>();
        if (hasCover) {
            urls.add(detail.coverUrl);
            seen.add(detail.coverUrl);
        }
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = contents.get(chapters.get(i).url);
            if (chapter == null) {
                continue;
            }
            for (int b = 0; b < chapter.blocks.size(); b++) {
                Chapter.Block block = chapter.blocks.get(b);
                if (block.image && block.value.length() > 0 && seen.add(block.value)) {
                    urls.add(block.value);
                }
            }
        }
        task.imagesTotal = urls.size();
        task.message = "插图 " + task.imagesTotal + " 张…";
        notifyNow(task, true);

        final ConcurrentHashMap<String, File> downloaded = new ConcurrentHashMap<String, File>();
        final ConcurrentHashMap<String, String> named = new ConcurrentHashMap<String, String>();
        final AtomicInteger numbered = new AtomicInteger(0);
        final AtomicInteger finished = new AtomicInteger(0);
        // consecutive connect-level failures: the host is probably unreachable, stop asking
        final AtomicInteger streak = new AtomicInteger(0);
        final AtomicBoolean gaveUp = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(IMAGE_THREADS);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < urls.size(); i++) {
                final String url = urls.get(i);
                final boolean cover = hasCover && i == 0;
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (task.cancelled) {
                            return;
                        }
                        if (gaveUp.get()) {
                            task.imagesFailed++;
                            task.imagesDone = finished.incrementAndGet();
                            notifyNow(task, false);
                            return;
                        }
                        try {
                            Log.d(TAG, "illustration start: " + url);
                            Fetcher.Response response =
                                    fetcher.fetch(url, 1, IMAGE_CONNECT_TIMEOUT, IMAGE_READ_TIMEOUT);
                            streak.set(0);
                            addBytes(task, response.length());
                            String name = cover
                                    ? "images/cover" + extensionFor(response.contentType, url)
                                    : String.format(Locale.US, "images/img_%04d%s",
                                    numbered.incrementAndGet(),
                                    extensionFor(response.contentType, url));
                            downloaded.put(url, writeTemp(dir, name, response.body));
                            named.put(url, name);
                        } catch (Exception error) {
                            // a missing illustration must not sink the whole book, but it should
                            // be visible: silent failures look exactly like a stalled download
                            task.imagesFailed++;
                            Log.w(TAG, "illustration failed: " + url + " -> "
                                    + Fetcher.describe(error));
                            if (streak.incrementAndGet() >= IMAGE_FAILURE_LIMIT) {
                                gaveUp.set(true);
                                Log.w(TAG, "illustration host unreachable, skipping the rest");
                            }
                        }
                        task.imagesDone = finished.incrementAndGet();
                        task.message = "插图 " + task.imagesDone + " / " + task.imagesTotal
                                + (gaveUp.get() ? "（图片站点无法访问，已跳过剩余）"
                                : task.imagesFailed > 0
                                ? "（" + task.imagesFailed + " 张失败）" : "");
                        notifyNow(task, false);
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (Exception ignored) {
                    // already accounted for
                }
            }
        } finally {
            pool.shutdown();
        }

        String coverName = "";
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            File file = downloaded.get(url);
            String name = named.get(url);
            if (file == null || name == null) {
                continue;
            }
            images.put(name, file);
            urlToName.put(url, name);
            if (hasCover && i == 0) {
                coverName = name;
            }
        }
        return coverName;
    }

    private void rewriteImageReferences(ConcurrentHashMap<String, Chapter> contents,
                                        HashMap<String, String> urlToName) {
        for (Chapter chapter : contents.values()) {
            for (int b = 0; b < chapter.blocks.size(); b++) {
                Chapter.Block block = chapter.blocks.get(b);
                if (!block.image) {
                    continue;
                }
                String name = urlToName.get(block.value);
                chapter.blocks.set(b, Chapter.Block.image(name == null ? "" : name));
            }
        }
    }

    /** Keeps only the chapters the user picked, dropping volumes that end up empty. */
    private static ArrayList<Outline> prune(ArrayList<Outline> nodes, ArrayList<Outline> wanted) {
        HashSet<String> keep = new HashSet<String>();
        for (int i = 0; i < wanted.size(); i++) {
            keep.add(wanted.get(i).url);
        }
        return pruneLevel(nodes, keep);
    }

    private static ArrayList<Outline> pruneLevel(ArrayList<Outline> nodes, HashSet<String> keep) {
        ArrayList<Outline> result = new ArrayList<Outline>();
        for (int i = 0; i < nodes.size(); i++) {
            Outline node = nodes.get(i);
            if (!node.volume) {
                if (keep.contains(node.url)) {
                    result.add(node);
                }
                continue;
            }
            ArrayList<Outline> children = pruneLevel(node.children, keep);
            if (children.isEmpty()) {
                continue;
            }
            Outline copy = Outline.volume(node.title, node.level);
            copy.children.addAll(children);
            result.add(copy);
        }
        return result;
    }

    private static void collectChapters(ArrayList<Outline> nodes, ArrayList<Outline> out) {
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).collectChapters(out);
        }
    }

    private static ArrayList<Outline> filter(ArrayList<Outline> chapters,
                                             ArrayList<String> selection) {
        if (selection == null || selection.isEmpty()) {
            return chapters;
        }
        HashSet<String> wanted = new HashSet<String>(selection);
        ArrayList<Outline> result = new ArrayList<Outline>();
        for (int i = 0; i < chapters.size(); i++) {
            if (wanted.contains(chapters.get(i).url)) {
                result.add(chapters.get(i));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    /** Volatile long is not enough for += across the worker threads, so serialise it. */
    private void addBytes(Task task, long count) {
        synchronized (task) {
            task.bytesDone += count;
        }
    }

    private File writeTemp(File dir, String name, byte[] body) throws IOException {
        File target = new File(dir, name.substring(name.lastIndexOf('/') + 1));
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(body);
        } finally {
            closeQuietly(out);
        }
        return target;
    }

    private void cleanupPictures(Task task) {
        deleteRecursively(new File(imageDir, task.book.id));
    }

    /** Records the finished book so the 已下载 page can list, open and re-process it. */
    private void recordLibrary(Task task, BookDetail detail, EpubBuilder.Result result,
                               Storage.Sink sink) {
        try {
            LibraryStore.Item item = new LibraryStore.Item();
            item.bookId = detail.id.length() > 0 ? detail.id : task.book.id;
            item.title = detail.title.length() > 0 ? detail.title : task.book.title;
            item.author = detail.author;
            item.uri = sink.uri == null ? "" : sink.uri;
            item.location = sink.location == null ? "" : sink.location;
            item.bytes = resolveSize(item.uri, item.location);
            item.chapters = result.chapters;
            item.images = result.images;
            item.finishedAt = System.currentTimeMillis();
            new LibraryStore(appContext).add(item);
        } catch (Exception ignored) {
            // the book is on disk either way; only the history entry is lost
        }
    }

    /** Reads the size of the file we just wrote, through MediaStore or the plain path. */
    private long resolveSize(String uriString, String path) {
        if (uriString != null && uriString.length() > 0) {
            android.database.Cursor cursor = null;
            try {
                cursor = appContext.getContentResolver().query(
                        android.net.Uri.parse(uriString),
                        new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } catch (Exception ignored) {
                // fall through to the path
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (path != null && path.length() > 0) {
            long length = new File(path).length();
            if (length > 0) {
                return length;
            }
        }
        return 0L;
    }

    static String extensionFor(String contentType, String url) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (type.indexOf("png") >= 0) {
            return ".png";
        }
        if (type.indexOf("gif") >= 0) {
            return ".gif";
        }
        if (type.indexOf("webp") >= 0) {
            return ".webp";
        }
        if (type.indexOf("bmp") >= 0) {
            return ".bmp";
        }
        if (type.indexOf("svg") >= 0) {
            return ".svg";
        }
        if (type.indexOf("jpeg") >= 0 || type.indexOf("jpg") >= 0) {
            return ".jpg";
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        if (dot > 0 && lower.length() - dot <= 5) {
            String guess = lower.substring(dot);
            if (".png".equals(guess) || ".gif".equals(guess) || ".webp".equals(guess)
                    || ".jpg".equals(guess) || ".jpeg".equals(guess)) {
                return ".jpeg".equals(guess) ? ".jpg" : guess;
            }
        }
        return ".jpg";
    }

    private void convert(BookDetail detail) {
        detail.title = t2s.convert(detail.title);
        detail.author = t2s.convert(detail.author);
        detail.genre = t2s.convert(detail.genre);
        detail.description = t2s.convert(detail.description);
        convertOutline(detail.outline);
    }

    private void convertOutline(ArrayList<Outline> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            Outline node = nodes.get(i);
            if (node.title.length() > 0) {
                node.title = t2s.convert(node.title);
            }
            convertOutline(node.children);
        }
    }

    private void convert(Chapter chapter) {
        chapter.title = t2s.convert(chapter.title);
        chapter.note = t2s.convert(chapter.note);
        for (int i = 0; i < chapter.blocks.size(); i++) {
            Chapter.Block block = chapter.blocks.get(i);
            if (!block.image) {
                chapter.blocks.set(i, Chapter.Block.text(t2s.convert(block.value)));
            }
        }
    }

    private void checkCancelled(Task task) throws IOException {
        if (task.cancelled) {
            throw new IOException("已取消");
        }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursively(children[i]);
                }
            }
        }
        file.delete();
    }

    public void shutdown() {
        chapters.shutdownNow();
        try {
            chapters.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- notifying

    private void notifyNow(final Task task, boolean force) {
        final Listener target = listener;
        if (target == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 200) {
            return;
        }
        lastNotify = now;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            target.onTaskChanged(task);
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onTaskChanged(task);
                }
            }
        });
    }
}
