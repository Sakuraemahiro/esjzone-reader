package com.esjzone.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;

/**
 * Keeps downloads running while the app is in the background or dismissed.
 *
 * <p>A plain thread would be killed with the activity, so the work lives in a foreground
 * service: the notification doubles as a progress display, and the queue in {@link QueueStore}
 * lets the next launch pick up whatever was interrupted.</p>
 */
public class DownloadService extends Service {

    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 0x5E1;

    private Handler handler;
    private Runnable ticker;
    private Downloader downloader;
    private QueueStore queue;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, DownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
            // without the service the download still runs in-process, just not in background
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        downloader = Downloader.get(this);
        queue = new QueueStore(this);
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(null));
        resumePending();
        acquireWakeLock();
        startTicker();
        return START_STICKY;
    }

    /** Re-queues anything that was interrupted, so closing the app does not lose the job. */
    private void resumePending() {
        ArrayList<QueueStore.Job> pending = queue.pending();
        for (int i = 0; i < pending.size(); i++) {
            QueueStore.Job job = pending.get(i);
            if (downloader.taskFor(job.toBook()) != null) {
                continue;
            }
            downloader.enqueue(job.toBook(), job.chapterUrls, job.textOnly);
        }
    }

    private void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = new Runnable() {
            @Override
            public void run() {
                ArrayList<Downloader.Task> active = downloader.activeTasks();
                if (active.isEmpty()) {
                    releaseWakeLock();
                    stopForeground(true);
                    stopSelf();
                    ticker = null;
                    return;
                }
                NotificationManager manager =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                manager.notify(NOTIFICATION_ID, buildNotification(active));
                handler.postDelayed(this, 1200);
            }
        };
        handler.postDelayed(ticker, 600);
    }

    private Notification buildNotification(ArrayList<Downloader.Task> active) {
        Intent open = new Intent(this, DownloadsActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentIntent(pending)
                .setOngoing(true);

        if (active == null || active.isEmpty()) {
            builder.setContentText("正在准备下载…").setProgress(0, 0, true);
        } else {
            StringBuilder text = new StringBuilder();
            int totalChapters = 0;
            int doneChapters = 0;
            long bytes = 0;
            for (int i = 0; i < active.size(); i++) {
                Downloader.Task task = active.get(i);
                totalChapters += task.chaptersTotal;
                doneChapters += Math.min(task.chaptersDone, task.chaptersTotal);
                bytes += task.bytesDone;
                if (text.length() > 0) {
                    text.append(" · ");
                }
                text.append(task.title.length() > 0 ? task.title : task.book.title);
                if (i >= 1 && active.size() > 2) {
                    text.append(" 等 ").append(active.size()).append(" 本");
                    break;
                }
            }
            builder.setContentText(text.toString() + "　" + Downloader.humanBytes(bytes));
            int max = totalChapters > 0 ? totalChapters : 0;
            builder.setProgress(max, doneChapters, max == 0);
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "下载", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("后台下载书籍时的进度通知");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "esjzone:downloads");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(30 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        if (ticker != null && handler != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
