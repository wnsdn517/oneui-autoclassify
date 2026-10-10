package com.auto.classify.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small JSON-over-HTTPS helper for the AI services, with errors worded for the user. */
final class Http {

    private Http() {
    }

    /** POST JSON; [header] = [value] is sent when given (API keys). Returns the reply text. */
    static String post(String endpoint, String body, String header, String value) throws Exception {
        return send("POST", endpoint, body, header, value);
    }

    static String get(String endpoint, String header, String value) throws Exception {
        return send("GET", endpoint, null, header, value);
    }

    /** POST JSON and hand each "data:" line of a server-sent-event reply to [onData]. */
    static void stream(String endpoint, String body, String header, String value,
                       java.util.function.Consumer<String> onData) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        if (header != null) c.setRequestProperty(header, value);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            String text = read(c.getErrorStream());
            c.disconnect();
            fail(code, text);
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String d = line.substring(5).trim();
                    if (!d.isEmpty() && !"[DONE]".equals(d)) onData.accept(d);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void fail(int code, String text) {
        switch (code) {
            case 400:
                throw new HttpError(code, "요청 거부(400) " + brief(text));
            case 401:
            case 403:
                throw new HttpError(code, "키가 틀렸거나 권한 없음(" + code + ") " + brief(text));
            case 404:
                throw new HttpError(code, "모델 이름이나 주소가 틀림(404)");
            case 402:
                throw new HttpError(code, "결제가 필요함(402)");
            case 429:
                throw new HttpError(code, "사용량 한도 초과(429) · 잠시 후 다시 시도하세요");
            default:
                if (code >= 500) throw new HttpError(code, "서버 오류(" + code + ")");
                throw new HttpError(code, "요청 거부(" + code + ")");
        }
    }

    private static String send(String method, String endpoint, String body, String header, String value) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        if (header != null) c.setRequestProperty(header, value);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        switch (code) {
            case 400:
                throw new HttpError(code, "요청 거부(400) " + brief(text));
            case 401:
            case 403:
                throw new HttpError(code, "키가 틀렸거나 권한 없음(" + code + ") " + brief(text));
            case 404:
                throw new HttpError(code, "모델 이름이나 주소가 틀림(404)");
            case 402:
                throw new HttpError(code, "결제가 필요함(402)");
            case 429:
                throw new HttpError(code, "사용량 한도 초과(429) · 잠시 후 다시 시도하세요");
            default:
                if (code >= 500) throw new HttpError(code, "서버 오류(" + code + ")");
                if (code >= 400) throw new HttpError(code, "요청 거부(" + code + ")");
        }
        return text;
    }

    /** An HTTP failure with its status code (404 = wrong model, used to pick another one). */
    static final class HttpError extends IllegalStateException {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Reply text of an OpenAI-style chat completion (or the body itself if it isn't one). */
    static String chatText(String text) throws Exception {
        String t = text.trim();
        if (t.startsWith("{")) {
            JSONArray choices = new JSONObject(t).optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                return choices.getJSONObject(0).getJSONObject("message").optString("content");
            }
        }
        return text;
    }

    private static String brief(String errorBody) {
        try {
            JSONObject o = new JSONObject(errorBody);
            Object e = o.opt("error");
            if (e instanceof JSONObject) return ((JSONObject) e).optString("message");
            if (e != null) return String.valueOf(e);
        } catch (Exception ignored) {
        }
        return errorBody.length() > 120 ? errorBody.substring(0, 120) : errorBody;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = s.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        }
    }
}
