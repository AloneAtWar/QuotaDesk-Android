package com.quotadesk.mobile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 电脑端 Quota Desk HTTP API 的最小客户端：
 * - POST {origin}/api/pair 原生配对换取只读令牌（与远程网页同一契约）
 * - GET  {origin}/api/v1/quota/snapshot 拉取额度快照
 * 全部为阻塞调用，须在后台线程执行。
 */
public final class QuotaApi {

    /** 服务端返回的非 2xx 状态；code 为 HTTP 状态码，messageText 为服务端 message（可能为空） */
    public static final class ApiException extends IOException {
        public final int status;
        public final String serverCode;
        public final String messageText;

        ApiException(int status, String serverCode, String messageText) {
            super(messageText == null || messageText.isEmpty() ? "HTTP " + status : messageText);
            this.status = status;
            this.serverCode = serverCode;
            this.messageText = messageText;
        }
    }

    public static final class PairResult {
        public final String token;
        public final String deviceId;
        public final String deviceName;

        PairResult(String token, String deviceId, String deviceName) {
            this.token = token;
            this.deviceId = deviceId;
            this.deviceName = deviceName;
        }
    }

    private QuotaApi() {
    }

    public static PairResult pair(String origin, String pairingKey, String deviceId, String deviceName) throws IOException {
        String body;
        try {
            body = new JSONObject()
                    .put("pairingKey", pairingKey)
                    .put("id", deviceId)
                    .put("name", deviceName)
                    .toString();
        } catch (JSONException error) {
            throw new IOException("构造配对请求失败");
        }
        JSONObject response = exchange("POST", origin + "/api/pair", null, body);
        String token = response.optString("token", "");
        if (token.isEmpty()) throw new IOException("电脑端没有返回访问令牌");
        return new PairResult(token, response.optString("deviceId", deviceId), response.optString("deviceName", deviceName));
    }

    public static JSONObject fetchSnapshot(String origin, String token) throws IOException {
        return exchange("GET", origin + "/api/v1/quota/snapshot", token, null);
    }

    private static JSONObject exchange(String method, String url, String bearer, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            String payload = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
            JSONObject json = null;
            try {
                json = payload.isEmpty() ? new JSONObject() : new JSONObject(payload);
            } catch (JSONException ignored) {
            }
            if (status < 200 || status >= 300) {
                throw new ApiException(status,
                        json == null ? "" : json.optString("error", ""),
                        json == null ? "" : json.optString("message", ""));
            }
            return json == null ? new JSONObject() : json;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(java.io.InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }
}
