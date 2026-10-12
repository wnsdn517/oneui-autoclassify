package com.auto.classify;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import com.auto.classify.core.Classifier;
import com.auto.classify.core.ColorEngine;
import com.auto.classify.core.Config;
import com.auto.classify.core.Directives;
import com.auto.classify.core.Prefs;
import com.auto.classify.core.PlayModel;
import com.auto.classify.core.SmartClassifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classification runs here, in the module app's process, so the launcher only does layout
 * work and the bundled model never lives in the launcher's memory.
 *
 * call("classify", null, {pkgs: String[], labels: String[]})
 *   -> {targets: String[], reasons: String[]}  (null entries = leave the app alone)
 */
public class ClassifierProvider extends ContentProvider {

    private static final String TAG = "AutoClassify";
    private static PlayModel play;
    private static boolean playFailed;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if ("ai_test".equals(method) && (caller == Process.SHELL_UID || caller == Process.ROOT_UID) && arg != null) {
            // Command-line check of the AI engines: content call ... --method ai_test --arg "요청" [--extra engine:s:online]
            Bundle out = new Bundle();
            long t0 = System.currentTimeMillis();
            try {
                String engine = extras == null ? "offline" : extras.getString("engine", "offline");
                com.auto.classify.ai.RuleAssistant.Result r = "offline".equals(engine)
                        ? com.auto.classify.ai.RuleAssistant.runOffline(getContext(), arg, new java.util.ArrayList<>())
                        : com.auto.classify.ai.RuleAssistant.run(getContext(),
                        com.auto.classify.ai.ApiLlm.fromPrefs(getContext()), arg, new java.util.ArrayList<>());
                out.putString("rules", r.rules);
                out.putString("raw", r.raw);
            } catch (Throwable t) {
                out.putString("error", Log.getStackTraceString(t));
            }
            out.putLong("ms", System.currentTimeMillis() - t0);
            return out;
        }
        if (("ai_models".equals(method) || "ai_organize".equals(method))
                && (caller == Process.SHELL_UID || caller == Process.ROOT_UID)) {
            // Command-line checks with the saved key; nothing is stored.
            // content call ... --method ai_models | --method ai_organize --arg "지시"
            Bundle out = new Bundle();
            try {
                com.auto.classify.ai.ApiLlm llm = com.auto.classify.ai.ApiLlm.fromPrefs(getContext());
                if (llm == null && !"brief".equals(arg)) throw new IllegalStateException("no API key");
                if ("ai_models".equals(method) && "brief".equals(arg)) {
                    long t = System.currentTimeMillis();
                    com.auto.classify.ai.AiOrganizer.warmUp(getContext());
                    long[] s = com.auto.classify.ai.AiOrganizer.lastTimes;
                    out.putString("ms", "list " + s[0] + " / category " + s[1] + " / developer " + s[2]
                            + " / total " + (System.currentTimeMillis() - t));
                    return out;
                }
                if ("ai_models".equals(method)) {
                    List<String> models = llm.listModels();
                    out.putString("models", String.join(",", models));
                    out.putString("best", com.auto.classify.ai.ApiLlm.bestModel(models));
                    out.putString("saved", llm.model());
                } else {
                    com.auto.classify.ai.AiOrganizer.Plan plan = com.auto.classify.ai.AiOrganizer.organize(getContext(), llm, arg, 2);
                    Map<String, Integer> sizes = new LinkedHashMap<>();
                    for (String f : plan.assign.values()) sizes.merge(f, 1, Integer::sum);
                    out.putString("folders", sizes.toString());
                    out.putString("assign", com.auto.classify.ai.AiOrganizer.save(plan.assign));
                    out.putString("settings", String.join(" / ", plan.settings));
                    out.putString("note", plan.note);
                    out.putInt("tokens", plan.tokens);
                    out.putString("model", llm.model());
                }
            } catch (Throwable t) {
                out.putString("error", String.valueOf(t));
            }
            return out;
        }
        if (!"classify".equals(method)) return null;
        if (!callerAllowed()) return null;
        String[] pkgs = extras == null ? null : extras.getStringArray("pkgs");
        String[] labels = extras == null ? null : extras.getStringArray("labels");
        String modeOverride = null;
        if (pkgs == null && arg != null && Binder.getCallingUid() != Process.myUid()) {
            // Command-line testing: content call ... --arg pkg1,pkg2 [--extra mode:s:category]
            pkgs = arg.split(",");
            modeOverride = extras == null ? null : extras.getString("mode");
        }
        if (pkgs == null) return null;
        if (labels == null || labels.length != pkgs.length) {
            labels = new String[pkgs.length];
            PackageManager pm = getContext().getPackageManager();
            for (int i = 0; i < pkgs.length; i++) {
                try {
                    labels[i] = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkgs[i], 0)));
                } catch (PackageManager.NameNotFoundException e) {
                    labels[i] = pkgs[i];
                }
            }
        }
        try {
            return classify(getContext(), pkgs, labels, true, modeOverride, extras == null ? null : extras.getString("newPkg"));
        } catch (Throwable t) {
            Log.e(TAG, "classify failed", t);
            Bundle err = new Bundle();
            err.putString("error", String.valueOf(t));
            return err;
        }
    }

    private boolean callerAllowed() {
        int uid = Binder.getCallingUid();
        // Self, plus adb shell/root for testing from the command line.
        if (uid == Process.myUid() || uid == Process.SHELL_UID || uid == Process.ROOT_UID) return true;
        PackageManager pm = getContext().getPackageManager();
        String[] names = pm.getPackagesForUid(uid);
        if (names == null) return false;
        for (String n : names) if (Config.LAUNCHER_PKG.equals(n)) return true;
        return false;
    }

    public static synchronized PlayModel play(Context context) {
        if (play == null && !playFailed) {
            try {
                play = PlayModel.load(context.getAssets());
            } catch (Exception e) {
                playFailed = true;
                Log.e(TAG, "play model load failed", e);
            }
        }
        return play;
    }

    /**
     * @param applyRules false gives the automatic decision alone (what cat: tokens match),
     *                   used to brief the AI rule builder
     */
    public static Bundle classify(Context context, String[] pkgs, String[] labels, boolean applyRules) {
        return classify(context, pkgs, labels, applyRules, null);
    }

    public static Bundle classify(Context context, String[] pkgs, String[] labels, boolean applyRules, String modeOverride) {
        return classify(context, pkgs, labels, applyRules, modeOverride, null);
    }

    /** @param newPkg a just-installed app; in AI mode the AI may be asked about it */
    public static Bundle classify(Context context, String[] pkgs, String[] labels, boolean applyRules, String modeOverride,
                                  String newPkg) {
        SharedPreferences p = Prefs.open(context);
        Set<String> excluded = lines(p.getString(Config.KEY_EXCLUDED, ""));
        String[] targets = new String[pkgs.length];
        String[] reasons = new String[pkgs.length];
        int[] pages = new int[pkgs.length];
        // Folder color per app from its rule; NO_COLOR = the rule set none.
        int[] colors = new int[pkgs.length];
        java.util.Arrays.fill(colors, NO_COLOR);
        Classifier rules = new Classifier(applyRules ? p.getString(Config.KEY_RULES, Config.DEFAULT_RULES) : null);

        String mode = modeOverride != null ? modeOverride : p.getString(Config.KEY_MODE, Config.MODE_CATEGORY);
        // True when the AI was asked about the new app and gave no answer (error, 429, limits).
        boolean aiMissed = false;
        if (Config.MODE_COLOR.equals(mode)) {
            colorTargets(context, p, pkgs, excluded, targets, reasons);
            Map<String, String> colorRenames = renames(p.getString(Config.KEY_FOLDER_NAMES, ""));
            // Rules apply on top of colors too (cat: matches the color group name).
            for (int i = 0; i < pkgs.length; i++) {
                if (excluded.contains(pkgs[i])) continue;
                String pk = pkgs[i];
                SmartClassifier.Result r = SmartClassifier.applyRules(rules, pk, labels[i],
                        SmartClassifier.colorResult(targets[i], reasons[i]), colorRenames,
                        () -> com.auto.classify.core.Developers.of(context.getPackageManager(), pk));
                targets[i] = r == null ? null : r.folder;
                reasons[i] = r == null ? null : r.reason;
                pages[i] = r == null ? 0 : r.page;
                if (r != null && r.color != null) colors[i] = r.color;
            }
        } else if (Config.MODE_AI.equals(mode)) {
            Map<String, String> ai = com.auto.classify.ai.AiOrganizer.load(p.getString(Config.KEY_AI_MAP, ""));
            Map<String, String> aiPages = com.auto.classify.ai.AiOrganizer.load(p.getString(Config.KEY_AI_PAGES, ""));
            for (int i = 0; i < pkgs.length; i++) {
                if (excluded.contains(pkgs[i])) continue;
                String pk = pkgs[i];
                // Apps the user placed by hand (and exclusions) still win over the AI.
                Classifier.Hit hit = rules.matchExact(pk, labels[i]);
                if (hit != null) {
                    if (Classifier.EXCLUDE.equals(hit.folder)) continue;
                    targets[i] = hit.folder;
                    reasons[i] = "직접 정한 위치";
                    pages[i] = hit.page;
                    if (hit.color != null) colors[i] = hit.color;
                    continue;
                }
                String folder = ai.get(pk);
                String why = "AI 분류";
                if (folder == null && pk.equals(newPkg)) {
                    folder = askAi(context, p, ai, pk, labels[i]);
                    why = "AI 분류 (설치할 때)";
                    if (folder == null && p.getBoolean(Config.KEY_AI_INSTALL, false)) aiMissed = true;
                }
                if (folder == null) continue;
                targets[i] = folder;
                reasons[i] = why;
                try {
                    String pg = aiPages.get(folder);
                    if (pg != null) pages[i] = Integer.parseInt(pg);
                } catch (NumberFormatException ignored) {
                }
            }
        } else {
            Set<String> signals = new HashSet<>();
            for (String s : p.getString(Config.KEY_SIGNALS, Config.DEFAULT_SIGNALS).split(",")) {
                if (!s.trim().isEmpty()) signals.add(s.trim());
            }
            SmartClassifier sc = new SmartClassifier(
                    rules,
                    signals,
                    p.getInt(Config.KEY_THRESHOLD, Config.DEFAULT_THRESHOLD) / 100f,
                    renames(p.getString(Config.KEY_FOLDER_NAMES, "")),
                    context.getPackageManager(),
                    play(context));
            // "detail" (rule checks) asks for the detailed categories that cat: tokens match.
            sc.broad = !"detail".equals(modeOverride) && !p.getBoolean(Config.KEY_DETAILED, false);
            for (int i = 0; i < pkgs.length; i++) {
                if (excluded.contains(pkgs[i])) continue;
                SmartClassifier.Result r = Config.MODE_RULES.equals(mode) ? sc.rulesOnly(pkgs[i], labels[i])
                        : sc.classify(pkgs[i], labels[i]);
                if (r == null) continue;
                targets[i] = r.folder;
                reasons[i] = r.reason;
                pages[i] = r.page;
                if (r.color != null) colors[i] = r.color;
            }
        }
        Bundle out = new Bundle();
        out.putStringArray("targets", targets);
        out.putStringArray("reasons", reasons);
        out.putIntArray("pages", pages);
        out.putBoolean("aiMissed", aiMissed);
        if (Config.MODE_AI.equals(mode)) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(p.getString(Config.KEY_AI_ORDER, "[]"));
                String[] order = new String[arr.length()];
                for (int i = 0; i < order.length; i++) order[i] = arr.optString(i);
                out.putStringArray("folderOrder", order);
            } catch (org.json.JSONException ignored) {
            }
        }
        out.putIntArray("folderColors", colors);
        out.putStringArray("autoNames", autoNames(p));
        out.putStringArray("excludedPkgs", excluded.toArray(new String[0]));

        // Layout directives and the sort keys they need.
        Directives d = Directives.parse(applyRules ? p.getString(Config.KEY_RULES, Config.DEFAULT_RULES) : null);
        out.putStringArray("directives", d.toArray());
        if (d.needsColor()) {
            float[] keys = new float[pkgs.length];
            PackageManager pm = context.getPackageManager();
            for (int i = 0; i < pkgs.length; i++) keys[i] = colorKey(pm, pkgs[i]);
            out.putFloatArray("colorKeys", keys);
        }
        if (Directives.ICON.equals(d.folderColor)) {
            int[] main = new int[pkgs.length];
            PackageManager pm = context.getPackageManager();
            for (int i = 0; i < pkgs.length; i++) {
                try {
                    Integer c = ColorEngine.representative(ColorEngine.pixels(pm.getApplicationIcon(pkgs[i])),
                            ColorEngine.Method.CENTROID, 0.5f, 0.5f);
                    main[i] = c == null ? NO_COLOR : c;
                } catch (PackageManager.NameNotFoundException e) {
                    main[i] = NO_COLOR;
                }
            }
            out.putIntArray("mainColors", main);
        }
        if (d.needsInstallTime()) {
            long[] times = new long[pkgs.length];
            PackageManager pm = context.getPackageManager();
            for (int i = 0; i < pkgs.length; i++) {
                try {
                    times[i] = pm.getPackageInfo(pkgs[i], 0).firstInstallTime;
                } catch (PackageManager.NameNotFoundException e) {
                    times[i] = Long.MAX_VALUE;
                }
            }
            out.putLongArray("installed", times);
        }
        return out;
    }

    /**
     * Asks the AI where a new app goes (when the user turned that on), within the cooldown and
     * daily limit; the answer joins the stored assignment. Null = no answer (rules/unclassified apply).
     */
    private static String askAi(Context context, SharedPreferences p, Map<String, String> ai, String pkg, String label) {
        if (!p.getBoolean(Config.KEY_AI_INSTALL, false)) return null;
        String instruction = p.getString(Config.KEY_AI_REQUEST, "");
        com.auto.classify.ai.ApiLlm llm = com.auto.classify.ai.ApiLlm.fromPrefs(context);
        if (llm == null || instruction == null || instruction.trim().isEmpty()) return null;
        String skip = com.auto.classify.ai.AiUsage.takeAuto(context, p.getInt(Config.KEY_AI_COOLDOWN, 30), p.getInt(Config.KEY_AI_DAILY, 30));
        if (skip != null) {
            com.auto.classify.ai.AiUsage.record(context, com.auto.classify.ai.AiUsage.KIND_INSTALL, label + " 건너뜀: " + skip, false, 0, 0);
            return null;
        }
        try {
            llm.maxRetries = 1; // a new app shouldn't wait minutes on a busy service
            String folder = com.auto.classify.ai.AiOrganizer.classifyOne(context, llm, instruction, ai, pkg, label);
            if (folder != null) {
                ai.put(pkg, folder);
                p.edit().putString(Config.KEY_AI_MAP, com.auto.classify.ai.AiOrganizer.save(ai)).apply();
            }
            return folder;
        } catch (Exception e) {
            Log.w(TAG, "AI for new app failed", e);
            return null;
        }
    }

    private static void colorTargets(Context context, SharedPreferences p, String[] pkgs, Set<String> excluded,
                                     String[] targets, String[] reasons) {
        ColorEngine.Method method;
        try {
            method = ColorEngine.Method.valueOf(p.getString(Config.KEY_COLOR_METHOD, ColorEngine.Method.CENTROID.name()));
        } catch (IllegalArgumentException e) {
            method = ColorEngine.Method.CENTROID;
        }
        float x = p.getInt(Config.KEY_COLOR_X, 50) / 100f;
        float y = p.getInt(Config.KEY_COLOR_Y, 50) / 100f;
        PackageManager pm = context.getPackageManager();

        Map<String, int[]> pixels = new LinkedHashMap<>();
        for (String pkg : pkgs) {
            if (excluded.contains(pkg)) continue;
            try {
                pixels.put(pkg, ColorEngine.pixels(pm.getApplicationIcon(pkg)));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        Map<String, String> result = new HashMap<>();
        String reason;
        if (method == ColorEngine.Method.SIMILARITY) {
            Map<String, Integer> main = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> e : pixels.entrySet()) {
                main.put(e.getKey(), ColorEngine.representative(e.getValue(), ColorEngine.Method.CENTROID, x, y));
            }
            int groups = p.getInt(Config.KEY_COLOR_GROUPS, 6);
            if (main.size() >= Math.max(2, groups)) {
                // A full batch: recluster and remember the groups for later single installs.
                ColorEngine.Groups g = ColorEngine.similarity(main, groups);
                result.putAll(g.byPackage);
                p.edit().putString(Config.KEY_COLOR_CENTERS, centersJson(g)).apply();
            } else {
                List<float[]> centers = new ArrayList<>();
                List<String> names = new ArrayList<>();
                readCenters(p.getString(Config.KEY_COLOR_CENTERS, ""), centers, names);
                for (Map.Entry<String, Integer> e : main.entrySet()) {
                    if (e.getValue() != null) {
                        result.put(e.getKey(), ColorEngine.nearestGroup(e.getValue(), centers, names));
                    }
                }
            }
            reason = "색상 유사도";
        } else {
            for (Map.Entry<String, int[]> e : pixels.entrySet()) {
                String name = ColorEngine.classify(e.getValue(), method, x, y);
                if (name != null) result.put(e.getKey(), name);
            }
            reason = methodLabel(method);
        }
        for (int i = 0; i < pkgs.length; i++) {
            targets[i] = result.get(pkgs[i]);
            if (targets[i] != null) reasons[i] = reason;
        }
    }

    /** Marks "no color" in int arrays (a real ARGB can't be this value with full alpha unset this way). */
    public static final int NO_COLOR = 0x00FFFFFF;

    /**
     * Every folder name this module may have created: category names (and their renames), color
     * groups and spread groups. Folders with these names are "automatic" and may be cleaned up.
     */
    static String[] autoNames(SharedPreferences p) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>(java.util.Arrays.asList(SmartClassifier.defaultFolders()));
        names.addAll(renames(p.getString(Config.KEY_FOLDER_NAMES, "")).values());
        // Folders the AI made are automatic too.
        names.addAll(com.auto.classify.ai.AiOrganizer.load(p.getString(Config.KEY_AI_MAP, "")).values());
        names.remove(Classifier.LOOSE);
        for (String c : new String[]{"빨강", "주황", "노랑", "초록", "청록", "파랑", "보라", "분홍", "흑백", "흰색", "회색", "검정",
                "단색", "투톤", "다채로움"}) {
            names.add(c);
            names.add("밝은 " + c);
            names.add("짙은 " + c);
            for (int i = 2; i <= 12; i++) names.add(c + " " + i);
        }
        return names.toArray(new String[0]);
    }

    /**
     * Rainbow order for "sort by color": hue of the icon's main color, with grays after all
     * colors (ordered dark to light).
     */
    public static float colorKey(PackageManager pm, String pkg) {
        try {
            Integer c = ColorEngine.representative(ColorEngine.pixels(pm.getApplicationIcon(pkg)),
                    ColorEngine.Method.CENTROID, 0.5f, 0.5f);
            if (c == null) return Float.MAX_VALUE;
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(c, hsv);
            if (hsv[1] * hsv[2] < 0.18f) return 1000f + hsv[2];
            // Pinks and magentas sit next to red at the start, not after purple.
            return hsv[0] >= 300 ? hsv[0] - 360 : hsv[0];
        } catch (PackageManager.NameNotFoundException e) {
            return Float.MAX_VALUE;
        }
    }

    static String methodLabel(ColorEngine.Method m) {
        switch (m) {
            case POSITION:
                return "색상 위치";
            case AVERAGE:
                return "색상 평균";
            case MODE:
                return "색상 최빈값";
            case SPREAD:
                return "색상 표준편차";
            case SIMILARITY:
                return "색상 유사도";
            default:
                return "색상 중심점";
        }
    }

    private static String centersJson(ColorEngine.Groups g) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < g.centers.size(); i++) {
                float[] c = g.centers.get(i);
                arr.put(new JSONObject().put("name", g.names.get(i))
                        .put("lab", new JSONArray().put(c[0]).put(c[1]).put(c[2])));
            }
            return arr.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void readCenters(String json, List<float[]> centers, List<String> names) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray lab = o.getJSONArray("lab");
                centers.add(new float[]{(float) lab.getDouble(0), (float) lab.getDouble(1), (float) lab.getDouble(2)});
                names.add(o.getString("name"));
            }
        } catch (Exception ignored) {
        }
    }

    static Set<String> lines(String text) {
        Set<String> out = new HashSet<>();
        for (String line : text.split("\n")) if (!line.trim().isEmpty()) out.add(line.trim());
        return out;
    }

    static Map<String, String> renames(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String from = line.substring(0, eq).trim(), to = line.substring(eq + 1).trim();
            if (!from.isEmpty() && !to.isEmpty()) m.put(from, to);
        }
        return m;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
