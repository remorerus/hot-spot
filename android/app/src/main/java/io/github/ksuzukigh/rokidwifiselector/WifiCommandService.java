package io.github.ksuzukigh.rokidwifiselector;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.List;

/** Фоновое переключение Wi-Fi по команде агента. */
public final class WifiCommandService extends Service {
    private static final String TAG = "HotSpotCommand";
    private static final String CHANNEL_ID = "hot_spot_wifi";
    private static final int NOTIFICATION_ID = 38762;
    private static final long TIMEOUT_MS = 15000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager wifi;
    private String targetSsid = "";
    private int previousNetworkId = -1;
    private long startedAt;
    private boolean finished;

    @Override public void onCreate() {
        super.onCreate();
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Переключение Wi-Fi", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Фоновое подключение к сохранённой сети");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, makeNotification());
        targetSsid = intent == null ? "" : intent.getStringExtra(WifiCommandReceiver.EXTRA_SSID);
        if (targetSsid == null || targetSsid.trim().isEmpty()) {
            finish(false, "", "Не указано имя Wi-Fi сети");
        } else beginSwitch();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void beginSwitch() {
        if (wifi == null) { finish(false, targetSsid, "Wi-Fi недоступен"); return; }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            finish(false, targetSsid, "Откройте Хот Спот и разрешите доступ к геоданным");
            return;
        }
        WifiInfo current = wifi.getConnectionInfo();
        String currentSsid = current == null ? "" : WifiChoiceCatalog.normalize(current.getSSID());
        previousNetworkId = current == null ? -1 : current.getNetworkId();
        if (targetSsid.equals(currentSsid)) {
            finish(true, targetSsid, "Уже подключено");
            return;
        }
        List<WifiConfiguration> configured;
        try { configured = wifi.getConfiguredNetworks(); }
        catch (SecurityException e) {
            Log.e(TAG, "Cannot read saved networks", e);
            finish(false, targetSsid, "Нет доступа к сохранённым сетям");
            return;
        }
        int targetId = -1;
        if (configured != null) for (WifiConfiguration item : configured) {
            if (targetSsid.equals(WifiChoiceCatalog.normalize(item.SSID))) {
                targetId = item.networkId; break;
            }
        }
        if (targetId < 0) {
            finish(false, targetSsid, "Сеть не сохранена в очках. Добавьте её через Hi Rokid");
            return;
        }
        boolean accepted;
        try {
            accepted = wifi.enableNetwork(targetId, true);
            if (accepted) wifi.reconnect();
        } catch (RuntimeException e) {
            Log.e(TAG, "Wi-Fi switch request failed", e);
            accepted = false;
        }
        if (!accepted) {
            finish(false, targetSsid, "Android отклонил переключение Wi-Fi");
            return;
        }
        startedAt = System.currentTimeMillis();
        handler.postDelayed(this::pollConnection, 500);
    }

    @SuppressWarnings("deprecation")
    private void pollConnection() {
        if (finished) return;
        WifiInfo info = wifi.getConnectionInfo();
        String actual = info == null ? "" : WifiChoiceCatalog.normalize(info.getSSID());
        if (targetSsid.equals(actual)) {
            finish(true, targetSsid, "Подключение подтверждено");
            return;
        }
        if (System.currentTimeMillis() - startedAt >= TIMEOUT_MS) {
            if (previousNetworkId >= 0) {
                try { wifi.enableNetwork(previousNetworkId, true); wifi.reconnect(); }
                catch (RuntimeException e) { Log.e(TAG, "Could not restore previous network", e); }
            }
            finish(false, targetSsid, "Не удалось подключиться за 15 секунд. Возвращаю прежнюю сеть");
            return;
        }
        handler.postDelayed(this::pollConnection, 500);
    }

    private void finish(boolean success, String ssid, String message) {
        if (finished) return;
        finished = true;
        Intent result = new Intent(WifiCommandReceiver.ACTION_RESULT);
        result.putExtra(WifiCommandReceiver.EXTRA_SUCCESS, success);
        result.putExtra(WifiCommandReceiver.EXTRA_SSID, ssid);
        result.putExtra(WifiCommandReceiver.EXTRA_MESSAGE, message);
        sendBroadcast(result);
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
    }

    private Notification makeNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_wifi)
                    .setContentTitle("Хот Спот")
                    .setContentText("Подключаюсь к " + targetSsid)
                    .setOngoing(true).build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_wifi)
                .setContentTitle("Хот Спот")
                .setContentText("Выполняю команду Wi-Fi")
                .setOngoing(true).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}