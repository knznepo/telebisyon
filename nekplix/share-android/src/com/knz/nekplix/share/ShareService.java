package com.knz.nekplix.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

// Shares while it runs, with the screen off too: a notification says so and has Stop. It keeps the
// device awake and its Wi-Fi on (else the TV loses it mid-film), and stops by itself after two hours
// with no TV using it, so a forgotten share doesn't drain the battery.
public class ShareService extends Service implements Server.Listener {
    static final String STOP = "stop";
    private static final long IDLE_MS = 2 * 60 * 60 * 1000L;
    private static final int ID = 1;

    // For the app's screen.
    static volatile boolean running;
    static volatile String problem, nowPlaying;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Server server;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private WifiManager.MulticastLock multicast;
    private volatile long lastUsed;

    static void start(Context c) {
        Intent i = new Intent(c, ShareService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.startService(new Intent(c, ShareService.class).setAction(STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(ID, notification("Starting…"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID, notification("Starting…"));
        if (server != null) return START_STICKY;

        server = new Server(this, this);
        try {
            server.start();
        } catch (Exception e) {
            problem = "Couldn't start sharing: " + e.getMessage() + ". Is NEKPLIX Share already running on this device?";
            server = null;
            stopSelf();
            return START_NOT_STICKY;
        }
        problem = null;
        nowPlaying = null;
        running = true;
        lastUsed = System.currentTimeMillis();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nekplix:share");
        wake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_HIGH_PERF : WifiManager.WIFI_MODE_FULL, "nekplix:share");
        wifi.acquire();
        multicast = wm.createMulticastLock("nekplix:share"); // so the TV's "who's sharing?" broadcasts get through
        multicast.acquire();
        update();
        main.postDelayed(idle, 60000);
        return START_STICKY;
    }

    private final Runnable idle = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastUsed > IDLE_MS) { stopSelf(); return; }
            update();
            main.postDelayed(this, 60000);
        }
    };

    private void update() {
        String what = nowPlaying != null ? "Playing " + nowPlaying : "Open NEKPLIX on the TV: it finds this by itself.";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, notification(what));
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("share", "Sharing", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "share");
        } else b = new Notification.Builder(this);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ShareService.class).setAction(STOP), flags);
        return b.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Sharing movies with NEKPLIX as " + Server.deviceName(this))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop sharing", stop).build())
                .build();
    }

    @Override
    public void playing(String what, String tv) {
        lastUsed = System.currentTimeMillis();
        String name = what.substring(what.lastIndexOf('/') + 1);
        nowPlaying = name;
        main.post(this::update);
    }

    @Override
    public void used() { lastUsed = System.currentTimeMillis(); }

    @Override
    public void onDestroy() {
        running = false;
        nowPlaying = null;
        main.removeCallbacks(idle);
        if (server != null) server.stop();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (multicast != null && multicast.isHeld()) multicast.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
