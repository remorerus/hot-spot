package io.github.ksuzukigh.rokidwifiselector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

final class WifiController {
    private static final String ROKID_SETTINGS_ACTION = "com.rokid.os.master.assist.server.cmd";
    private static final String ROKID_ASSIST_SERVER_PACKAGE = "com.rokid.os.sprite.assistserver";
    private static final String ROKID_WIFI_ENABLED_SETTING = "[{\"key\":\"settings_wifi_enable\",\"value\":\"true\"}]";
    private final Context context;
    private final WifiManager wifi;

    WifiController(Context context) {
        this.context = context.getApplicationContext();
        this.wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    @SuppressLint("MissingPermission")
    JSONObject status() throws Exception {
        JSONObject out = new JSONObject();
        boolean enabled = wifi != null && wifi.isWifiEnabled();
        boolean connected = isWifiTransportActive();
        String ssid = currentSsid();
        out.put("ok", true);
        out.put("wifiEnabled", enabled);
        out.put("connected", connected);
        out.put("ssid", ssid);
        out.put("ssidAvailable", !ssid.isEmpty());
        out.put("wifiState", wifi == null ? -1 : wifi.getWifiState());
        return out;
    }

    @SuppressLint("MissingPermission")
    JSONObject networks() throws Exception {
        requireLocationPermission();
        if (wifi == null) throw new IllegalStateException("Служба Wi-Fi недоступна");
        if (!wifi.isWifiEnabled()) return new JSONObject().put("ok", true).put("wifiEnabled", false).put("networks", new JSONArray());
        try { wifi.startScan(); } catch (RuntimeException ignored) {}
        SystemClock.sleep(500);
        List<WifiConfiguration> configured = wifi.getConfiguredNetworks();
        List<ScanResult> scans = wifi.getScanResults();
        Set<String> nearby = new HashSet<>();
        Map<String,Integer> levels = new HashMap<>();
        if (scans != null) for (ScanResult scan : scans) {
            String name = normalize(scan.SSID);
            if (!name.isEmpty()) {
                nearby.add(name);
                Integer old = levels.get(name);
                if (old == null || scan.level > old) levels.put(name, scan.level);
            }
        }
        String current = currentSsid();
        Map<String,Integer> saved = new HashMap<>();
        if (configured != null) for (WifiConfiguration item : configured) {
            String name = normalize(item.SSID);
            if (!name.isEmpty() && nearby.contains(name)) saved.putIfAbsent(name, item.networkId);
        }
        // Always expose the active SSID, even if the scan result omits it.
        if (!current.isEmpty()) saved.putIfAbsent(current, -1);
        List<String> names = new ArrayList<>(saved.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        JSONArray array = new JSONArray();
        for (String name : names) {
            array.put(new JSONObject().put("ssid", name).put("current", name.equals(current))
                    .put("signalLevel", levels.containsKey(name) ? levels.get(name) : -100));
        }
        return new JSONObject().put("ok", true).put("wifiEnabled", true)
                .put("currentSsid", current).put("networks", array);
    }

    @SuppressLint("MissingPermission")
    JSONObject connect(String requestedSsid) throws Exception {
        String wanted = normalize(requestedSsid);
        if (wanted.isEmpty()) return error("ssid_required", "Не указано имя Wi-Fi сети");
        if (wifi == null || !wifi.isWifiEnabled()) return error("wifi_off", "Wi-Fi выключен");
        // Do not require a scan or location permission to confirm an already active network.
        String activeSsid = currentSsid();
        if (wanted.equals(activeSsid)) {
            JSONObject out = status();
            out.put("message", "Очки уже подключены к этой сети");
            return out;
        }
        requireLocationPermission();
        try { wifi.startScan(); } catch (RuntimeException ignored) {}
        SystemClock.sleep(500);
        List<WifiConfiguration> configured = wifi.getConfiguredNetworks();
        List<ScanResult> scans = wifi.getScanResults();
        Set<String> nearby = new HashSet<>();
        if (scans != null) for (ScanResult scan : scans) {
            String name = normalize(scan.SSID);
            if (!name.isEmpty()) nearby.add(name);
        }
        int targetId = -1;
        if (configured != null) for (WifiConfiguration item : configured) {
            if (wanted.equals(normalize(item.SSID)) && nearby.contains(wanted)) {
                targetId = item.networkId; break;
            }
        }
        if (targetId < 0) return error("not_saved_or_not_nearby",
                "Сеть не найдена среди сохранённых сетей поблизости. Добавьте её через Hi Rokid.");
        String previousSsid = currentSsid();
        WifiInfo previousInfo = wifi.getConnectionInfo();
        int previousId = previousInfo == null ? -1 : previousInfo.getNetworkId();
        if (wanted.equals(previousSsid)) {
            JSONObject out = status(); out.put("message", "Очки уже подключены к этой сети"); return out;
        }
        boolean accepted;
        try {
            accepted = wifi.enableNetwork(targetId, true);
            if (accepted) wifi.reconnect();
        } catch (SecurityException e) {
            return error("permission_denied", "Система запретила переключение Wi-Fi: " + safe(e));
        }
        if (!accepted) return error("switch_rejected", "Система не приняла запрос на переключение Wi-Fi");
        long until = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(400);
            if (wanted.equals(currentSsid())) {
                JSONObject out = status(); out.put("message", "Подключение подтверждено"); return out;
            }
        }
        if (previousId >= 0) {
            try { wifi.enableNetwork(previousId, true); wifi.reconnect(); } catch (RuntimeException ignored) {}
        }
        JSONObject out = error("connect_timeout", "Не удалось подтвердить подключение к выбранной сети за 15 секунд");
        out.put("previousSsid", previousSsid);
        out.put("restoreRequested", previousId >= 0);
        return out;
    }

    @SuppressLint("MissingPermission")
    JSONObject turnWifiOn() throws Exception {
        if (wifi == null) return error("wifi_unavailable", "Служба Wi-Fi недоступна");
        try {
            Intent intent = new Intent(ROKID_SETTINGS_ACTION);
            intent.setPackage(ROKID_ASSIST_SERVER_PACKAGE);
            intent.putExtra("cmd_type", "setting_change");
            intent.putExtra("value", ROKID_WIFI_ENABLED_SETTING);
            context.sendBroadcast(intent);
        } catch (RuntimeException ignored) {}
        if (wifi.isWifiEnabled()) return status();
        boolean accepted;
        try { accepted = wifi.setWifiEnabled(true); }
        catch (SecurityException e) { return error("permission_denied", "Система запретила включить Wi-Fi: " + safe(e)); }
        if (!accepted) return error("manual_required", "Прошивка запретила автоматическое включение. Включите Wi-Fi в системных настройках.");
        long until = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(300);
            if (wifi.isWifiEnabled()) return status();
        }
        return error("enable_timeout", "Wi-Fi не включился за 8 секунд");
    }

    private void requireLocationPermission() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Разрешите приложению Хот Спот доступ к местоположению: это нужно Android для чтения имён Wi-Fi сетей.");
    }
    private boolean isWifiTransportActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities candidate = cm.getNetworkCapabilities(network);
                if (candidate != null && candidate.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
            return false;
        } catch (RuntimeException e) {
            try {
                WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
                return info != null && info.getNetworkId() >= 0;
            } catch (RuntimeException ignored) { return false; }
        }
    }

    private String currentSsid() {
        try {
            WifiInfo info = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network active = cm == null ? null : cm.getActiveNetwork();
                NetworkCapabilities caps = active == null || cm == null ? null : cm.getNetworkCapabilities(active);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (cm != null) for (Network network : cm.getAllNetworks()) {
                        NetworkCapabilities candidate = cm.getNetworkCapabilities(network);
                        if (candidate != null && candidate.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { caps = candidate; break; }
                    }
                }
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.getTransportInfo() instanceof WifiInfo) info = (WifiInfo) caps.getTransportInfo();
            }
            if (info == null && wifi != null) info = wifi.getConnectionInfo();
            if (info == null) return "";
            String ssid = normalize(info.getSSID());
            if (ssid.equalsIgnoreCase("unknown ssid")) return "";
            return ssid;
        } catch (RuntimeException e) { return ""; }
    }
    static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
            value = value.substring(1, value.length() - 1);
        if ("<unknown ssid>".equalsIgnoreCase(value) || "unknown ssid".equalsIgnoreCase(value) || "0x".equalsIgnoreCase(value)) return "";
        return value;
    }
    private JSONObject error(String code, String message) throws Exception {
        return new JSONObject().put("ok", false).put("error", code).put("message", message);
    }
    private String safe(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}