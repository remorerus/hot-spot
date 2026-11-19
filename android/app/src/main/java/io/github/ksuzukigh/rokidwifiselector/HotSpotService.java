package io.github.ksuzukigh.rokidwifiselector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public final class HotSpotService extends Service {
    private static final String CHANNEL = "hotspot_bridge";
    private HotSpotHttpServer server;
    private Thread serverThread;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(38762, buildNotification());
        server = new HotSpotHttpServer(new WifiController(getApplicationContext()));
        serverThread = new Thread(server, "HotSpot-Local-Bridge");
        serverThread.start();
        Log.i("HotSpotService", "Local bridge started on 127.0.0.1:" + HotSpotConfig.PORT);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Хот Спот", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Локальный голосовой мост управления Wi-Fi");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Хот Спот активен")
                    .setContentText("Голосовое управление Wi-Fi готово")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true).build();
        }
        return new Notification.Builder(this)
                .setContentTitle("Хот Спот активен")
                .setContentText("Голосовое управление Wi-Fi готово")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true).build();
    }
}