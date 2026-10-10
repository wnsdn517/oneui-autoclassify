package com.auto.classify.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Log of every AI request (what, tokens, result) and the limits for automatic requests on
 * install: a cooldown between calls and a daily cap. Private to the app.
 */
public final class AiUsage {

    private static final String PREFS = "ai_usage";
    private static final String KEY_LOG = "log";
    private static final String KEY_LAST_AUTO = "last_auto";
    private static final int KEEP = 200;

    public static final String KIND_ORGANIZE = "정리";
    public static final String KIND_VERIFY = "검증";
    public static final String KIND_INSTALL = "새 앱";
    public static final String KIND_RULES = "규칙";
    public static final String KIND_NAMES = "이름";

    private AiUsage() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void record(Context c, String kind, String detail, boolean ok, int in, int out) {
        JSONArray log = log(c);
        try {
            log.put(new JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("detail", detail)
                    .put("ok", ok).put("in", in).put("out", out));
        } catch (Exception ignored) {
        }
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, log.length() - KEEP); i < log.length(); i++) kept.put(log.opt(i));
        prefs(c).edit().putString(KEY_LOG, kept.toString()).apply();
    }

    /** Oldest first. */
    public static synchronized JSONArray log(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_LOG, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static synchronized void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    /** {requests, tokens} since midnight. */
    public static long[] today(Context c) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long since = cal.getTimeInMillis();
        JSONArray log = log(c);
        long n = 0, tokens = 0;
        for (int i = 0; i < log.length(); i++) {
            JSONObject o = log.optJSONObject(i);
            if (o == null || o.optLong("t") < since || o.optInt("in") + o.optInt("out") == 0 && !o.optBoolean("ok")) continue;
            n++;
            tokens += o.optInt("in") + o.optInt("out");
        }
        return new long[]{n, tokens};
    }

    /** Automatic (install) requests made today. */
    private static int autoToday(Context c) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        JSONArray log = log(c);
        int n = 0;
        for (int i = 0; i < log.length(); i++) {
            JSONObject o = log.optJSONObject(i);
            if (o != null && o.optLong("t") >= cal.getTimeInMillis() && KIND_INSTALL.equals(o.optString("kind"))
                    && (o.optInt("in") > 0 || o.optBoolean("ok"))) n++;
        }
        return n;
    }

    /**
     * Reserves one automatic request.
     * @return null when allowed (and starts the cooldown), else why it was skipped
     */
    public static synchronized String takeAuto(Context c, int cooldownSec, int dailyMax) {
        long now = System.currentTimeMillis();
        long last = prefs(c).getLong(KEY_LAST_AUTO, 0);
        if (now - last < cooldownSec * 1000L) {
            return "쿨다운 중 (" + ((cooldownSec * 1000L - (now - last)) / 1000 + 1) + "초 남음)";
        }
        if (autoToday(c) >= dailyMax) return "오늘 자동 요청 한도 " + dailyMax + "회를 다 썼어요";
        prefs(c).edit().putLong(KEY_LAST_AUTO, now).apply();
        return null;
    }
}
