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
    private static Process sshdProcess;
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
            // 停止必须真正杀掉容器与全部服务（通知栏的「停止」= 关机语义）。
            // 绝不能放进 executor：runLoop 永久占着单线程（while 循环阻塞在
            // process.waitFor()），stop 任务会排在它后面永远执行不到——
            // 通知栏停止和设置页停止/重启因此全部失效。独立线程执行停止。
            new Thread(() -> {
                stopContainer();
                stopForeground(true);
                stopSelf();
            }, "dsh-harness-stop").start();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Starting container…"));
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
                updateNotification("Repairing node-pty native module…");
                boolean fixed = NodePtyFixer.fix(this, log);
                updateNotification(fixed
                        ? "node-pty fixed, starting…"
                        : "node-pty repair failed, see logs in Settings");
            }
            try {
                updateNotification("DeepSeek Harness running · Port " + prefs.getPort());
                startSshd(prefs, log);
                process = ProotRunner.startWeb(this, prefs.getPort(), log);
                running = true;
                int code = process.waitFor();
                running = false;
                if (!wantRun) break;
                restarts++;
                if (restarts > MAX_RESTART) {
                    updateNotification("Container exited repeatedly, stopped (see logs)");
                    break;
                }
                updateNotification("Container exited (" + code + "), restarting in " + 3 + " seconds…");
                Thread.sleep(3000);
            } catch (IOException e) {
                running = false;
                updateNotification("Start failed: " + e.getMessage());
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

    /** 启动容器内 sshd：老容器没有就先联网补装（失败不影响 Web 服务）。 */
    private void startSshd(Prefs prefs, File log) {
        BootstrapInstaller.ensureSshServerInstalled(this, log);
        if (!new File(ProotRunner.rootfsDir(this), "usr/sbin/sshd").isFile()) return;
        if (sshdProcess != null && sshdProcess.isAlive()) return;
        try {
            sshdProcess = ProotRunner.startSshd(this, prefs.getSshPort(), log);
        } catch (IOException e) {
            updateNotification("SSH start failed: " + e.getMessage());
        }
    }

    private void stopContainer() {
        // 状态先置 false：设置页/按钮立即反映"已停止"，
        // 不必等强杀兜底（最长 3s）走完
        running = false;
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                // 有界等待：容器卡住不退时不能无限阻塞（调用方可能在主线程）
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        Process s = sshdProcess;
        sshdProcess = null;
        if (s != null) {
            s.destroy();
            s.destroyForcibly();
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
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Harness Service",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("DeepSeek Harness container running status");
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
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
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

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 划卡清任务时最后一搏：趁进程还没被杀，尝试把服务重新拉起来。
        // Android 12+ 后台起前台服务可能抛异常（ForegroundServiceStartNotAllowed
        // 等），拦住即可；荣耀/MagicOS 这类划卡杀整进程的 ROM，最终要靠用户在
        // 系统设置里给本应用开「自启动/允许后台活动」（设置页有入口）。
        if (wantRun) {
            try {
                Intent restart = new Intent(this, HarnessService.class);
                restart.setAction(ACTION_START);
                startForegroundService(restart);
            } catch (Exception e) {
                // 后台启动限制：无系统侧授权时无法绕过，交给 START_STICKY 与用户授权兜底
            }
        }
        super.onTaskRemoved(rootIntent);
    }
}
