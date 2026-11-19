package io.github.ksuzukigh.rokidwifiselector;

import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class HotSpotHttpServer implements Runnable {
    private static final String TAG = "HotSpotHttp";
    private final WifiController controller;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running = true;
    private ServerSocket server;

    HotSpotHttpServer(WifiController controller) { this.controller = controller; }

    @Override public void run() {
        try {
            server = new ServerSocket(HotSpotConfig.PORT, 16, InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "Listening on 127.0.0.1:" + HotSpotConfig.PORT);
            while (running) {
                Socket socket = server.accept();
                clients.execute(() -> handle(socket));
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "HTTP server stopped", e);
        }
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        clients.shutdownNow();
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { respond(s.getOutputStream(), 400, jsonError("bad_request", "Некорректный запрос")); return; }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1].split("\\?", 2)[0];
            Map<String,String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    headers.put(key, line.substring(colon + 1).trim());
                    if ("content-length".equals(key)) {
                        try { contentLength = Math.min(8192, Integer.parseInt(headers.get(key))); } catch (Exception ignored) {}
                    }
                }
            }
            byte[] body = readBytes(in, contentLength);
            if ("GET".equals(method) && "/health".equals(path)) {
                respond(s.getOutputStream(), 200, new JSONObject().put("ok", true).put("service", "Хот Спот").put("version", "1.1.0-ru-hotspot"));
                return;
            }
            if (!HotSpotConfig.TOKEN.equals(headers.get("x-hotspot-token"))) {
                respond(s.getOutputStream(), 401, jsonError("unauthorized", "Неверный ключ локального моста")); return;
            }
            JSONObject result;
            try {
                if ("GET".equals(method) && "/v1/status".equals(path)) result = controller.status();
                else if ("GET".equals(method) && "/v1/networks".equals(path)) result = controller.networks();
                else if ("POST".equals(method) && "/v1/connect".equals(path)) {
                    JSONObject input = body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
                    result = controller.connect(input.optString("ssid", ""));
                } else if ("POST".equals(method) && "/v1/wifi/on".equals(path)) result = controller.turnWifiOn();
                else { respond(s.getOutputStream(), 404, jsonError("not_found", "Команда не найдена")); return; }
            } catch (SecurityException e) {
                result = jsonError("permission_required", e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Request failed: " + path, e);
                result = jsonError("internal_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            respond(s.getOutputStream(), result.optBoolean("ok", false) ? 200 : 400, result);
        } catch (Exception e) { Log.w(TAG, "Client request ended", e); }
    }

    private String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') out.write(value);
            if (out.size() > 8192) throw new IllegalArgumentException("HTTP-заголовок слишком большой");
        }
        if (value == -1 && out.size() == 0) return null;
        return out.toString("UTF-8");
    }
    private byte[] readBytes(InputStream in, int count) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (out.size() < count) {
            int read = in.read(buffer, 0, Math.min(buffer.length, count - out.size()));
            if (read < 0) break;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
    private JSONObject jsonError(String code, String message) throws Exception {
        return new JSONObject().put("ok", false).put("error", code).put("message", message == null ? code : message);
    }
    private void respond(OutputStream out, int status, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found" : "Bad Request";
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}