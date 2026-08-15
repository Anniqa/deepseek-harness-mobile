package com.dshmobile.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 前台服务：持有 proot 容器进程，保证后台存活。 */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.dshmobile.app.action.START";
    public static final String ACTION_STOP = "com.dshmobile.app.action.STOP";
    private static final String CHANNEL_ID = "harness";
    private static final int NOTIF_ID = 1001;
    private static final int MAX_RESTART = 5;

    private static Process process;
    private static boolean running;

    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean wantRun;

    public static boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    public static void startService(Context ctx) {
        Intent i = new Intent(ctx, HarnessService.class);
        i.setAction(ACTION_START);
        ctx.startForegroundService(i);
    }

    public static void stopService(Context ctx) {
        Intent i = new Intent(ctx, HarnessService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            wantRun = false;
            stopContainer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("正在启动容器…"));
        acquireWakeLock();
        wantRun = true;
        if (!isRunning()) {
            executor.execute(this::runLoop);
        }
        return START_STICKY;
    }

    private void runLoop() {
        Prefs prefs = Prefs.of(this);
        int restarts = 0;
        while (wantRun) {
            File log = new File(ProotRunner.baseDir(this), "dsh-web.log");
            // 启动前自检 node-pty：pty.node 缺失时 dsh web 必崩（plugin tree
            // failed to load），先就地修复再启动，避免无意义的崩溃-重启循环
            if (NodePtyFixer.needsFix(ProotRunner.rootfsDir(this))) {
                updateNotification("正在修复 node-pty 原生模块…");
                boolean fixed = NodePtyFixer.fix(this, log);
                updateNotification(fixed
                        ? "node-pty 修复完成，正在启动…"
                        : "node-pty 修复失败，请到设置查看日志");
            }
            try {
                updateNotification("DeepSeek Harness 运行中 · 端口 " + prefs.getPort());
                process = ProotRunner.startWeb(this, prefs.getPort(), log);
                running = true;
                int code = process.waitFor();
                running = false;
                if (!wantRun) break;
                restarts++;
                if (restarts > MAX_RESTART) {
                    updateNotification("容器多次退出，已停止（详见日志）");
                    break;
                }
                updateNotification("容器退出(" + code + ")，" + 3 + " 秒后重启…");
                Thread.sleep(3000);
            } catch (IOException e) {
                running = false;
                updateNotification("启动失败: " + e.getMessage());
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        releaseWakeLock();
        if (!wantRun) {
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopContainer() {
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            p.destroyForcibly();
        }
        running = false;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dshmobile:harness");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Harness 服务",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("DeepSeek Harness 容器运行状态");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HarnessService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 31
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this, CHANNEL_ID);
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "停止", stopPi).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        wantRun = false;
        stopContainer();
        releaseWakeLock();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
