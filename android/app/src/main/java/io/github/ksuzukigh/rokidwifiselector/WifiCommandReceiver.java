package io.github.ksuzukigh.rokidwifiselector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Командный вход для AIUI-агента «Хот Спот». */
public final class WifiCommandReceiver extends BroadcastReceiver {
    public static final String ACTION_CONNECT = "io.github.ksuzukigh.rokidwifiselector.CONNECT";
    public static final String ACTION_RESULT = "io.github.ksuzukigh.rokidwifiselector.RESULT";
    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_MESSAGE = "message";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_CONNECT.equals(intent.getAction())) return;
        String ssid = intent.getStringExtra(EXTRA_SSID);
        if (ssid == null || ssid.trim().isEmpty()) {
            Intent result = new Intent(ACTION_RESULT);
            result.putExtra(EXTRA_SUCCESS, false);
            result.putExtra(EXTRA_SSID, "");
            result.putExtra(EXTRA_MESSAGE, "Не указано имя Wi-Fi сети");
            context.sendBroadcast(result);
            return;
        }
        Intent service = new Intent(context, WifiCommandService.class);
        service.putExtra(EXTRA_SSID, ssid.trim());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
        else context.startService(service);
    }
}