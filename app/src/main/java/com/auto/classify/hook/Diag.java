package com.auto.classify.hook;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Self-diagnosis and logging inside the launcher. Each feature is checked at startup and
 * whenever it fails at runtime; the settings app asks for the result and decides what to show.
 *
 * Severity: FATAL (module unusable), ERROR (a feature is broken), WARN (may misbehave).
 */
public final class Diag {

    public static final String FATAL = "fatal";
    public static final String ERROR = "error";
    public static final String WARN = "warn";

    /** Feature ids, shared with the settings app. */
    public static final String F_CORE = "core";
    public static final String F_LIVE = "live";
    public static final String F_ORGANIZE = "organize";
    public static final String F_BACKUP = "backup";

    private static final int MAX_LINES = 600;
    private static final long MAX_FILE = 256 * 1024;

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final Map<String, JSONObject> checks = new LinkedHashMap<>();
    private static File file;

    private Diag() {
    }

    static synchronized void init(Context context) {
        file = new File(context.getFilesDir(), "autoclassify.log");
        for (String l : lines) append(l);
    }

    public static synchronized void log(String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + msg;
        lines.addLast(line);
        while (lines.size() > MAX_LINES) lines.removeFirst();
        if (file != null) append(line);
    }

    private static void append(String line) {
        try {
            if (file.length() > MAX_FILE) {
                File old = new File(file.getPath() + ".1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException ignored) {
        }
    }

    /** Records the outcome of a check; a later call for the same id replaces it. */
    public static synchronized void check(String id, String feature, boolean ok, String severity, String message) {
        try {
            checks.put(id, new JSONObject()
                    .put("id", id)
                    .put("feature", feature)
                    .put("ok", ok)
                    .put("severity", severity)
                    .put("message", message == null ? "" : message));
        } catch (JSONException ignored) {
        }
        if (!ok) log("CHECK FAILED [" + severity + "] " + id + ": " + message);
    }

    public static void ok(String id, String feature) {
        check(id, feature, true, WARN, null);
    }

    /** A feature threw at runtime; reported as an ERROR on that feature. */
    public static void failed(String feature, String what, Throwable t) {
        check("runtime." + feature, feature, false, ERROR, what + ": " + t);
        log(what + " 실패: " + Log.getStackTraceString(t));
    }

    static synchronized JSONObject report(String launcherVersion, boolean attached) throws JSONException {
        JSONArray arr = new JSONArray();
        for (JSONObject c : checks.values()) arr.put(c);
        return new JSONObject()
                .put("launcherVersion", launcherVersion)
                .put("attached", attached)
                .put("checks", arr);
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        if (file != null) {
            File old = new File(file.getPath() + ".1");
            sb.append(read(old)).append(read(file));
        } else {
            for (String l : lines) sb.append(l).append('\n');
        }
        // Keep the reply within a comfortable broadcast size.
        int max = 200 * 1024;
        return sb.length() > max ? sb.substring(sb.length() - max) : sb.toString();
    }

    private static String read(File f) {
        if (!f.exists()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
