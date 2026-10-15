package com.auto.classify.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.auto.classify.ModuleMain;
import com.auto.classify.core.Config;
import com.auto.classify.core.Directives;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import de.robv.android.xposed.XposedHelpers;

/**
 * Runs inside the launcher.
 * <ul>
 *   <li>New installs are filed live through the launcher's own folder events.</li>
 *   <li>"Organize" is a DB-level plan ({@link Planner}) that can be previewed, then applied in
 *       one transaction followed by a launcher restart. It also moves apps already in folders.</li>
 *   <li>Every apply/restore is preceded by an automatic backup.</li>
 * </ul>
 */
public final class Engine {

    public static final class Settings {
        public boolean enabled = true;
        public boolean createFolders = true;
        public int minApps = 2;
        public boolean skipMoved;
        /** Take apps out of stale automatic folders when organizing. */
        public boolean cleanStale = true;
        public String unclassified = Config.UNCLASSIFIED_FREE;
        public String unclassifiedFolder = "기타";
        public int unclassifiedPage = 1;
        public boolean screenOffTidy = true;
        /** Feature ids (Diag.F_*) the user switched off after a failed self-check. */
        public Set<String> disabled = new HashSet<>();
    }

    private static final long ADD_DELAY_MS = 600;
    private static final long RETRY_MS = 400;
    private static final int MAX_RETRIES = 15;
    private static final long FOLDER_POLL_MS = 200;
    private static final int FOLDER_POLL_TRIES = 15;
    /** The launcher finishes setting up a new folder after it appears; rename after that. */
    private static final long RENAME_DELAY_MS = 1200;
    private static final long RENAME_VERIFY_MS = 1500;
    private static final int RENAME_TRIES = 4;
    /** Same value as ClassifierProvider.NO_COLOR (the provider lives in the module app). */
    private static final int NO_COLOR = 0x00FFFFFF;
    private static final String GRID_URI = "content://com.sec.android.app.launcher.settings";

    private final Context context;
    private final ClassLoader cl;
    private final Supplier<Settings> settingsLoader;
    /** The user's rules and AI instructions as JSON, saved with every backup. */
    private final Supplier<String> instructionsLoader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Tracker tracker;
    private final Backup backup;
    private final Planner planner;

    private Settings settings = new Settings();
    private volatile LauncherBridge bridge;
    private Object dataSource;

    public Engine(Context context, ClassLoader cl, Supplier<Settings> settingsLoader,
                  Supplier<String> instructionsLoader) {
        this.context = context;
        this.cl = cl;
        this.settingsLoader = settingsLoader;
        this.instructionsLoader = instructionsLoader;
        this.tracker = new Tracker(context.getFilesDir());
        this.backup = new Backup(context);
        this.planner = new Planner(context);
        Diag.init(context);
        reload();
        selfCheck();
    }

    /** Startup checks for everything that doesn't need the apps-screen view model. */
    private void selfCheck() {
        String version = launcherVersion();
        if (Arrays.asList(Config.TESTED_LAUNCHER_VERSIONS).contains(version)) {
            Diag.ok("version", Diag.F_CORE);
        } else {
            Diag.check("version", Diag.F_CORE, false, Diag.WARN,
                    "테스트하지 않은 One UI 홈 버전 " + version);
        }
        try {
            String schema = planner.checkSchema();
            if (schema == null) Diag.ok("db.schema", Diag.F_ORGANIZE);
            else Diag.check("db.schema", Diag.F_ORGANIZE, false, Diag.ERROR, "배치 DB 구조가 다릅니다: " + schema);
        } catch (Throwable t) {
            Diag.check("db.schema", Diag.F_ORGANIZE, false, Diag.ERROR, "배치 DB를 열 수 없습니다: " + t);
        }
        planner.setGridSource(this::grid);
        new Thread(this::checkGrid, "AutoClassify-grid").start();
        File probe = new File(context.getFilesDir(), "autoclassify_backup");
        if ((probe.isDirectory() || probe.mkdirs()) && probe.canWrite()
                && context.getDatabasePath("OneUI.db").canRead()) {
            Diag.ok("backup.fs", Diag.F_BACKUP);
        } else {
            Diag.check("backup.fs", Diag.F_BACKUP, false, Diag.ERROR, "백업 폴더에 쓸 수 없거나 DB를 읽을 수 없습니다");
        }
    }

    /** Reads the grid once and reports it in the self-check. */
    private void checkGrid() {
        int[] g = grid();
        if (g != null) {
            planner.setGrid(g[0], g[1]);
            Diag.ok("grid", Diag.F_ORGANIZE);
            log("앱스 화면 격자 " + g[0] + "x" + g[1]);
        } else {
            Diag.check("grid", Diag.F_ORGANIZE, false, Diag.WARN,
                    "홈 화면 앱 타일 수를 감지하지 못해 4x6으로 가정했어요. 페이지가 밀리거나 의도와 다르게 배치될 수 있어요 (앱스 화면을 열면 다시 읽어요)");
        }
    }

    /**
     * The apps-screen grid {cols, rows} in use right now: from the live view model, else from
     * the launcher's settings provider. null when neither answers.
     */
    private int[] grid() {
        LauncherBridge b = bridge;
        if (b != null) {
            try {
                int[] g = b.applistGrid();
                if (g != null) return g;
            } catch (Throwable ignored) {
            }
        }
        try {
            Bundle r = context.getContentResolver().call(Uri.parse(GRID_URI), "get_apps_cell_dimension", null, null);
            int cols = r == null ? 0 : r.getInt("cols"), rows = r == null ? 0 : r.getInt("rows");
            if (cols > 0 && rows > 0) return new int[]{cols, rows};
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String launcherVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private boolean off(String feature) {
        return settings.disabled.contains(feature);
    }

    public void attach(Object applistVm, Object honeyDataSource) {
        this.dataSource = honeyDataSource;
        this.bridge = new LauncherBridge(cl, applistVm, honeyDataSource);
        new Thread(this::checkGrid, "AutoClassify-grid").start();
    }

    private void reload() {
        try {
            settings = settingsLoader.get();
        } catch (Throwable t) {
            log("설정을 읽지 못해 기본값 사용: " + t);
            settings = new Settings();
        }
    }

    // ---- commands -----------------------------------------------------------------------

    public void command(String cmd, String arg) {
        try {
            dispatch(cmd, arg);
        } catch (Throwable t) {
            Diag.failed(Diag.F_CORE, "명령 '" + cmd + "'", t);
            try {
                report("'" + cmd + "' 처리 중 오류: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    private void dispatch(String cmd, String arg) {
        switch (cmd) {
            case "reload":
                reload();
                break;
            case "plan":
                reload();
                runPlan();
                break;
            case "apply":
                reload();
                runApply();
                break;
            case "place":
                if (arg != null) placeLive(arg);
                break;
            case "moveitem": // "itemId,anchorId,before(0/1)" for testing the live move
                try {
                    String[] a = arg.split(",");
                    log("moveitem " + arg + " -> " + bridge.moveNextTo(Integer.parseInt(a[0].trim()),
                            Integer.parseInt(a[1].trim()), "1".equals(a[2].trim())));
                } catch (Throwable t) {
                    Diag.failed(Diag.F_LIVE, "moveitem", t);
                }
                break;
            case "applylayout":
                runApplyLayout(arg);
                break;
            case "backups":
                sendBackups();
                break;
            case "layout":
                new Thread(() -> {
                    try {
                        reply(Config.ACTION_LAYOUT, planner.currentLayout().toString());
                    } catch (Throwable t) {
                        Diag.failed(Diag.F_ORGANIZE, "layout", t);
                    }
                }, "AutoClassify-layout").start();
                break;
            case "backup":
                backupNow();
                break;
            case "restart":
                // Ends this Home process; the system starts Home again, with the newest hook loaded.
                report("One UI 홈을 다시 시작합니다");
                main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
                break;
            case "restore":
                restore(arg);
                break;
            case "folders":
                dumpFolders();
                break;
            case "health":
                try {
                    reply(Config.ACTION_HEALTH, Diag.report(launcherVersion(), bridge != null).toString());
                } catch (Throwable t) {
                    log("진단 실패: " + t);
                }
                break;
            case "logs":
                reply(Config.ACTION_LOGS, "launcher " + launcherVersion() + "\n" + Diag.dump());
                break;
            case "status":
                log("tracking:\n" + tracker.dump() + "backups: " + String.join(", ", backup.list()));
                break;
            default:
                report("홈이 모르는 명령이에요: " + cmd + " (One UI 홈을 재시작해 최신 모듈을 불러오세요)");
        }
    }

    private void runPlan() {
        if (off(Diag.F_ORGANIZE)) {
            report("정리 기능이 꺼져 있습니다 (자체 진단에서 문제 발견)");
            return;
        }
        new Thread(() -> {
            try {
                Planner.Plan plan = planner.plan(this::targets, settings.createFolders, settings.minApps);
                String json = plan.toJson().toString();
                log("미리보기: " + plan.summary());
                reply(Config.ACTION_PLAN, json);
            } catch (Throwable t) {
                Diag.failed(Diag.F_ORGANIZE, "preview", t);
                report("미리보기 실패: " + t.getMessage());
            }
        }, "AutoClassify-plan").start();
    }

    private void runApply() {
        if (off(Diag.F_ORGANIZE)) {
            report("정리 기능이 꺼져 있습니다 (자체 진단에서 문제 발견)");
            return;
        }
        new Thread(() -> {
            try {
                String snap = off(Diag.F_BACKUP) ? "없음" : snapshot();
                Planner.Plan plan = planner.apply(this::targets, settings.createFolders, settings.minApps);
                if (plan.isEmpty()) {
                    report("이미 정리되어 있습니다");
                    return;
                }
                for (Planner.Move mv : plan.moves) tracker.filed(mv.app.pkg, mv.to, -1);
                report("앱 " + plan.moves.size() + "개를 옮기고 페이지를 정리했습니다 (백업 " + snap + "). 홈을 다시 시작합니다");
                // The launcher's in-memory model is now stale; a fresh process reloads it.
                main.postDelayed(() -> Process.killProcess(Process.myPid()), 400);
            } catch (Throwable t) {
                Diag.failed(Diag.F_ORGANIZE, "apply", t);
                report("정리 실패: " + t.getMessage());
            }
        }, "AutoClassify-apply").start();
    }

    /** Writes a layout the user arranged by hand in the preview. */
    private void runApplyLayout(String json) {
        if (off(Diag.F_ORGANIZE) || json == null) {
            report("정리 기능이 꺼져 있습니다 (자체 진단에서 문제 발견)");
            return;
        }
        new Thread(() -> {
            try {
                String snap = off(Diag.F_BACKUP) ? "없음" : snapshot();
                int n = planner.applyLayout(new JSONObject(json));
                report("직접 배치한 대로 적용했습니다 (앱 " + n + "개 이동, 백업 " + snap + "). 홈을 다시 시작합니다");
                main.postDelayed(() -> Process.killProcess(Process.myPid()), 400);
            } catch (Throwable t) {
                Diag.failed(Diag.F_ORGANIZE, "applylayout", t);
                report("적용 실패: " + t.getMessage());
            }
        }, "AutoClassify-applylayout").start();
    }

    private void sendBackups() {
        if (off(Diag.F_BACKUP)) {
            reply(Config.ACTION_BACKUPS, "[]");
            return;
        }
        new Thread(() -> {
            try {
                JSONArray list = new JSONArray();
                String[] names = backup.list();
                for (int i = names.length - 1; i >= 0; i--) {
                    JSONObject o = new JSONObject().put("name", names[i]);
                    File db = backup.file(names[i], "OneUI.db");
                    if (db.exists()) o.put("layout", planner.layoutOf(backup.workingCopy(names[i])));
                    String instructions = backup.instructions(names[i]);
                    try {
                        if (instructions != null) o.put("instructions", new JSONObject(instructions));
                    } catch (JSONException broken) {
                        Diag.log("백업 지시 파일을 읽지 못함: " + names[i]);
                    }
                    list.put(o);
                }
                reply(Config.ACTION_BACKUPS, list.toString());
            } catch (Throwable t) {
                Diag.failed(Diag.F_BACKUP, "backup list", t);
                report("백업 목록 실패: " + t.getMessage());
            }
        }, "AutoClassify-backups").start();
    }

    private void restore(String name) {
        String target = name == null || name.isEmpty() ? backup.latest() : name;
        if (target == null) {
            report("백업이 없습니다");
            return;
        }
        if (off(Diag.F_BACKUP)) {
            report("백업 기능이 꺼져 있습니다");
            return;
        }
        try {
            String safety = snapshot(); // so the restore itself can be undone
            report(target + " 시점으로 되돌립니다 (현재 상태는 " + safety + "에 백업). 홈을 다시 시작합니다");
        } catch (Exception e) {
            report("현재 상태 백업 실패로 되돌리기를 중단했습니다: " + e.getMessage());
            return;
        }
        main.postDelayed(() -> {
            try {
                backup.restoreAndRestart(target);
            } catch (Exception e) {
                Diag.failed(Diag.F_BACKUP, "restore", e);
                report("되돌리기 실패: " + e.getMessage());
            }
        }, 400);
    }

    // ---- live filing of new installs ----------------------------------------------------

    public void onPackageAdded(String pkg) {
        if (!settings.enabled || off(Diag.F_LIVE)) return;
        main.postDelayed(() -> {
            try {
                waitForApp(pkg, 0);
            } catch (Throwable t) {
                Diag.failed(Diag.F_LIVE, "file new app", t);
            }
        }, ADD_DELAY_MS);
    }

    /** New apps whose page placement should be fixed at the next screen off. */
    private final java.util.Set<String> pendingTidy = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** Screen off: fix new apps' pages if that is all the plan would change. */
    public void onScreenOff() {
        if (restartPending) {
            // A failed live move can leave the dragged app transparent; a fresh Home process clears it.
            restartPending = false;
            log("앱 이동 중 오류가 있어 화면이 꺼진 사이 홈을 다시 시작해요");
            main.postDelayed(() -> Process.killProcess(Process.myPid()), 300);
            return;
        }
        if (pendingTidy.isEmpty() || !settings.screenOffTidy || off(Diag.F_ORGANIZE)) return;
        java.util.Set<String> mine = new java.util.HashSet<>(pendingTidy);
        new Thread(() -> {
            try {
                reload();
                Planner.Plan plan = planner.plan(this::targets, settings.createFolders, settings.minApps);
                pendingTidy.removeAll(mine);
                if (!plan.changed) return;
                for (Planner.Move mv : plan.moves) {
                    if (!mine.contains(mv.app.pkg)) {
                        log("화면 꺼짐 정리 건너뜀: " + app(mv.app.pkg) + "도 옮겨야 해서 직접 정리가 필요해요");
                        return;
                    }
                }
                String snap = off(Diag.F_BACKUP) ? "없음" : snapshot();
                planner.apply(this::targets, settings.createFolders, settings.minApps);
                log("화면 꺼짐 정리: " + apps(mine) + " 페이지 맞춤 (백업 " + snap + ")");
                main.postDelayed(() -> Process.killProcess(Process.myPid()), 300);
            } catch (Throwable t) {
                Diag.failed(Diag.F_ORGANIZE, "screen-off tidy", t);
            }
        }, "AutoClassify-tidy").start();
    }

    public void onPackageRemoved(String pkg) {
        tracker.forget(pkg);
    }

    private void waitForApp(String pkg, int attempt) {
        if (bridge == null) return;
        Object app = bridge.findTopLevelApp(pkg);
        if (app == null) {
            if (attempt < MAX_RETRIES) main.postDelayed(() -> {
                try {
                    waitForApp(pkg, attempt + 1);
                } catch (Throwable t) {
                    Diag.failed(Diag.F_LIVE, "file new app", t);
                }
            }, RETRY_MS);
            else log("새 앱 " + app(pkg) + ": 앱스 화면에 안 보여 건너뜀");
            return;
        }
        // Snapshot the loose apps on the main thread, classify off it (cross-process call).
        List<Object> loose = new ArrayList<>();
        List<String> pkgs = new ArrayList<>(), labels = new ArrayList<>();
        for (Object item : bridge.topLevelItems()) {
            if (!bridge.isApp(item)) continue;
            ComponentName cn = bridge.component(item);
            if (cn == null) continue;
            loose.add(item);
            pkgs.add(cn.getPackageName());
            labels.add(bridge.label(item));
        }
        new Thread(() -> {
            Planner.Targets targets = targets(pkgs, labels, pkg);
            Map<String, String[]> t = targets.byPkg;
            main.post(() -> {
                try {
                    if (targets.aiMissed) {
                        retryAfterAiMiss(pkg);
                        return;
                    }
                    fileNewApp(pkg, app, loose, t);
                } catch (Throwable e) {
                    Diag.failed(Diag.F_LIVE, "file new app", e);
                }
            });
        }, "AutoClassify-classify").start();
    }

    private static final int AI_RETRIES = 3;
    private static final long AI_RETRY_MS = 90_000;
    private final Map<String, Integer> aiTries = new HashMap<>();

    /**
     * The AI didn't answer for a new app (rate limit, error). Moving it by guesswork would only
     * misplace it, so leave it where the launcher put it and ask again a bit later.
     */
    private void retryAfterAiMiss(String pkg) {
        int n = aiTries.merge(pkg, 1, Integer::sum);
        if (n > AI_RETRIES) {
            aiTries.remove(pkg);
            log("새 앱 " + app(pkg) + ": AI가 계속 답하지 않아 그대로 둠 (정리할 때 분류돼요)");
            return;
        }
        log("새 앱 " + app(pkg) + ": AI 분류 실패 · 그대로 두고 잠시 후 다시 시도 (" + n + "/" + AI_RETRIES + ")");
        main.postDelayed(() -> {
            try {
                waitForApp(pkg, 0);
            } catch (Throwable t) {
                Diag.failed(Diag.F_LIVE, "file new app", t);
            }
        }, AI_RETRY_MS);
    }

    /** Set when a live move may have left an item mid-drag (invisible but taking space); Home is restarted at screen off. */
    private volatile boolean restartPending;

    private void fileNewApp(String pkg, Object app, List<Object> loose, Map<String, String[]> t) {
        aiTries.remove(pkg);
        String[] mine = t.get(pkg);
        String folder = mine == null ? null : mine[0];
        if (folder == null && Config.UNCLASSIFIED_FOLDER.equals(settings.unclassified)
                && !settings.unclassifiedFolder.trim().isEmpty()) {
            folder = settings.unclassifiedFolder.trim();
            mine = new String[]{folder, "분류 안 됨"};
        }
        if (folder == null || Planner.LOOSE.equals(folder)) {
            // Its page may still be wrong (e.g. it landed on a page rules reserve): fix that
            // when the screen goes off, since moving loose apps needs a Home restart.
            boolean pagePin = mine != null && mine.length > 2 && mine[2] != null && !"0".equals(mine[2]);
            if (pagePin || !Config.UNCLASSIFIED_KEEP.equals(settings.unclassified)) {
                placeLive(pkg);
            } else {
                log("새 앱 " + app(pkg) + ": 맞는 폴더 없음 · 그대로 둠");
            }
            return;
        }
        log("새 앱 " + app(pkg) + " → " + folder + " (" + mine[1] + ")");
        Object existing = bridge.findFolderByLabel(folder);
        if (existing != null) {
            addApps(existing, folder, Collections.singletonList(app));
        } else if (settings.createFolders) {
            List<Object> group = new ArrayList<>();
            for (Object item : loose) {
                ComponentName cn = bridge.component(item);
                String[] other = cn == null ? null : t.get(cn.getPackageName());
                if (other != null && folder.equals(other[0])) group.add(item);
            }
            if (group.size() >= Math.max(1, settings.minApps)) createFolder(folder, group);
            else {
                log("새 앱 " + app(pkg) + ": '" + folder + "' 폴더가 없고 앱이 " + group.size() + "개라 최소 " + settings.minApps + "개 미만 · 폴더 밖에 둠");
                // It stays loose, so its page may be wrong.
                placeLive(pkg);
            }
        }
    }

    /**
     * Puts one new loose app on the page the plan wants, live: next to its planned neighbour
     * through the launcher's own move. Falls back to the screen-off fix when that isn't possible.
     */
    private void placeLive(String pkg) {
        new Thread(() -> {
            try {
                Planner.Plan plan = planner.plan(this::targets, settings.createFolders, settings.minApps);
                int[] now = find(plan.before, pkg), then = find(plan.after, pkg);
                if (now == null || then == null || now[0] == then[0]) {
                    log("새 앱 " + app(pkg) + ": 이미 맞는 페이지");
                    return;
                }
                int[] spot = neighbour(plan, pkg); // {appId, anchorId, before(1/0)}
                if (spot == null) {
                    // e.g. it belongs on a new, empty page: nothing to drop it next to.
                    pendingTidy.add(pkg);
                    log("새 앱 " + app(pkg) + ": 옮길 자리가 없어 화면이 꺼질 때 맞춤");
                    return;
                }
                main.post(() -> {
                    try {
                        if (bridge != null && bridge.moveNextTo(spot[0], spot[1], spot[2] == 1)) {
                            log("새 앱 " + app(pkg) + ": 맞는 페이지로 옮김");
                        } else {
                            pendingTidy.add(pkg);
                            log("새 앱 " + app(pkg) + ": 바로 옮기지 못해 화면이 꺼질 때 맞춤");
                        }
                    } catch (Throwable t) {
                        pendingTidy.add(pkg);
                        restartPending = true;
                        Diag.failed(Diag.F_LIVE, "live page move", t);
                    }
                });
            } catch (Throwable t) {
                pendingTidy.add(pkg);
                Diag.failed(Diag.F_LIVE, "live page plan", t);
            }
        }, "AutoClassify-place").start();
    }

    /**
     * Where the plan puts a loose app: its id and a neighbour that is on that same page both
     * now and after the plan. null when it is already on the planned page (or isn't loose).
     */
    private static int[] neighbour(Planner.Plan plan, String pkg) throws org.json.JSONException {
        int[] now = find(plan.before, pkg), then = find(plan.after, pkg);
        if (now == null || then == null || now[0] == then[0]) return null;
        org.json.JSONArray page = plan.after.getJSONArray("pages").getJSONArray(then[0]);
        // Closest neighbours first: the one before it (drop after), then the one after it (drop before).
        for (int d = 1; d < page.length(); d++) {
            for (int side : new int[]{-1, 1}) {
                int i = then[1] + side * d;
                if (i < 0 || i >= page.length()) continue;
                org.json.JSONObject o = page.getJSONObject(i);
                int id = o.optInt("id", -1);
                int[] where = findId(plan.before, id);
                if (id < 0 || where == null || where[0] != then[0]) continue;
                return new int[]{now[2], id, side > 0 ? 1 : 0};
            }
        }
        return null;
    }

    /** {page, index, id} of a loose app in a layout. */
    private static int[] find(org.json.JSONObject layout, String pkg) throws org.json.JSONException {
        org.json.JSONArray pages = layout.getJSONArray("pages");
        for (int p = 0; p < pages.length(); p++) {
            org.json.JSONArray items = pages.getJSONArray(p);
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject o = items.getJSONObject(i);
                if ("app".equals(o.optString("type")) && pkg.equals(o.optString("pkg"))) return new int[]{p, i, o.optInt("id", -1)};
            }
        }
        return null;
    }

    /** {page, index} of a top-level item id in a layout. */
    private static int[] findId(org.json.JSONObject layout, int id) throws org.json.JSONException {
        org.json.JSONArray pages = layout.getJSONArray("pages");
        for (int p = 0; p < pages.length(); p++) {
            org.json.JSONArray items = pages.getJSONArray(p);
            for (int i = 0; i < items.length(); i++) if (items.getJSONObject(i).optInt("id", -2) == id) return new int[]{p, i};
        }
        return null;
    }

    /**
     * Asks the module app's classifier for a batch. Apps the user moved by hand are dropped
     * here when "skip moved" is on, since only the launcher knows about those.
     * @return pkg -> {folder, reason}; empty when the classifier is unavailable
     */
    private Planner.Targets targets(List<String> pkgs, List<String> labels) {
        return targets(pkgs, labels, null);
    }

    /** @param newPkg the app just installed (the classifier may ask the AI about it) */
    private Planner.Targets targets(List<String> pkgs, List<String> labels, String newPkg) {
        Planner.Targets t = new Planner.Targets();
        Map<String, String[]> out = t.byPkg;
        if (pkgs.isEmpty()) return t;
        try {
            Bundle in = new Bundle();
            in.putStringArray("pkgs", pkgs.toArray(new String[0]));
            in.putStringArray("labels", labels.toArray(new String[0]));
            if (newPkg != null) in.putString("newPkg", newPkg);
            Bundle r = context.getContentResolver().call(
                    Uri.parse("content://" + Config.CLASSIFIER_AUTHORITY), "classify", null, in);
            if (r == null) throw new IllegalStateException("분류기 응답 없음 (AutoClassify 앱 설치 확인)");
            if (r.containsKey("error")) throw new IllegalStateException("분류기 오류: " + r.getString("error"));
            String[] folders = r.getStringArray("targets");
            String[] reasons = r.getStringArray("reasons");
            int[] pages = r.getIntArray("pages");
            t.directives = Directives.fromArray(r.getStringArray("directives"));
            float[] colorKeys = r.getFloatArray("colorKeys");
            long[] installed = r.getLongArray("installed");
            int[] folderColors = r.getIntArray("folderColors");
            int[] mainColors = r.getIntArray("mainColors");
            for (int i = 0; i < pkgs.size(); i++) {
                String pk = pkgs.get(i);
                if (colorKeys != null && i < colorKeys.length) t.colorKey.put(pk, colorKeys[i]);
                if (installed != null && i < installed.length) t.installed.put(pk, installed[i]);
                if (folderColors != null && i < folderColors.length && folderColors[i] != NO_COLOR) t.folderColor.put(pk, folderColors[i]);
                if (mainColors != null && i < mainColors.length && mainColors[i] != NO_COLOR) t.mainColor.put(pk, mainColors[i]);
            }
            String[] autoNames = r.getStringArray("autoNames");
            if (autoNames != null) t.autoNames.addAll(java.util.Arrays.asList(autoNames));
            t.cleanStale = settings.cleanStale;
            t.aiMissed = r.getBoolean("aiMissed");
            t.aiMode = r.getBoolean("aiMode");
            String[] folderOrder = r.getStringArray("folderOrder");
            if (folderOrder != null) t.folderOrder.addAll(Arrays.asList(folderOrder));
            t.unclassified = settings.unclassified;
            t.unclassifiedFolder = settings.unclassifiedFolder;
            t.unclassifiedPage = settings.unclassifiedPage;
            if (Config.UNCLASSIFIED_FOLDER.equals(settings.unclassified)) t.autoNames.add(settings.unclassifiedFolder.trim());
            String[] ex = r.getStringArray("excludedPkgs");
            if (ex != null) t.excluded.addAll(java.util.Arrays.asList(ex));
            for (int i = 0; i < pkgs.size() && folders != null && i < folders.length; i++) {
                String pkg = pkgs.get(i);
                if (folders[i] == null) continue;
                if (settings.skipMoved && tracker.isDetached(pkg)) continue;
                out.put(pkg, new String[]{folders[i], reasons == null ? null : reasons[i],
                        pages == null || pages[i] <= 0 ? null : String.valueOf(pages[i])});
            }
            Diag.ok("classifier", Diag.F_CORE);
        } catch (Throwable e) {
            Diag.check("classifier", Diag.F_CORE, false, Diag.ERROR, "분류기를 호출하지 못했습니다: " + e.getMessage());
            log("분류기 호출 실패: " + e.getMessage());
        }
        return t;
    }

    private void addApps(Object folder, String name, List<Object> apps) {
        try {
            List<ComponentName> comps = new ArrayList<>();
            for (Object app : apps) {
                ComponentName cn = bridge.component(app);
                if (cn == null) continue;
                comps.add(cn);
                tracker.beginOwnChange(cn.getPackageName());
            }
            boolean ok = bridge.addToFolder(folder, comps, Process.myUserHandle());
            int folderId = bridge.id(folder);
            for (ComponentName cn : comps) tracker.filed(cn.getPackageName(), name, folderId);
            log("'" + name + "' 폴더에 앱 " + comps.size() + "개 넣음" + (ok ? "" : " (실패)"));
            if (!ok) {
                Diag.check("live.add", Diag.F_LIVE, false, Diag.WARN, "'" + name + "' 폴더에 앱을 넣지 못함, 계획된 페이지로 옮겨 둠");
                for (ComponentName cn : comps) placeLive(cn.getPackageName());
            }
        } catch (Throwable t) {
            Diag.failed(Diag.F_LIVE, "add to folder", t);
        }
    }

    private void createFolder(String name, List<Object> apps) {
        try {
            List<Integer> before = bridge.folderIds();
            for (Object app : apps) {
                ComponentName cn = bridge.component(app);
                if (cn != null) tracker.beginOwnChange(cn.getPackageName());
            }
            // The launcher puts the folder where the last listed app was, and a new install sits
            // at the very end of the apps screen. List the earliest-placed app last so the
            // folder takes its slot instead of jumping to the end.
            List<Object> ordered = new ArrayList<>(apps);
            Map<Object, Long> pos = new HashMap<>();
            for (Object app : ordered) pos.put(app, bridge.position(app));
            ordered.sort((a, b) -> Long.compare(pos.get(a), pos.get(b)));
            ordered.add(ordered.remove(0));
            if (!bridge.createFolder(ordered)) {
                log("'" + name + "' 폴더 만들기 거부됨");
                Diag.check("live.create", Diag.F_LIVE, false, Diag.WARN, "'" + name + "' 폴더를 만들지 못함, 앱은 계획된 페이지로 옮겨 둠");
                for (Object app : apps) {
                    ComponentName cn = bridge.component(app);
                    if (cn != null) placeLive(cn.getPackageName());
                }
                return;
            }
            pollNewFolder(name, apps, before, 0);
        } catch (Throwable t) {
            Diag.failed(Diag.F_LIVE, "create folder", t);
        }
    }

    private void pollNewFolder(String name, List<Object> apps, List<Integer> before, int attempt) {
        for (Object item : bridge.topLevelItems()) {
            if (!bridge.isFolder(item) || before.contains(bridge.id(item))) continue;
            int id = bridge.id(item);
            for (Object app : apps) {
                ComponentName cn = bridge.component(app);
                if (cn != null) tracker.filed(cn.getPackageName(), name, id);
            }
            log("'" + name + "' 폴더 만듦 (앱 " + apps.size() + "개)");
            main.postDelayed(() -> rename(id, name, 0), RENAME_DELAY_MS);
            return;
        }
        if (attempt < FOLDER_POLL_TRIES) {
            main.postDelayed(() -> pollNewFolder(name, apps, before, attempt + 1), FOLDER_POLL_MS);
        } else {
            log("'" + name + "' 폴더 만들기: 폴더가 나타나지 않음");
            Diag.check("live.create", Diag.F_LIVE, false, Diag.WARN, "'" + name + "' 폴더가 나타나지 않음, 앱은 계획된 페이지로 옮겨 둠");
            for (Object app : apps) {
                ComponentName cn = bridge.component(app);
                if (cn != null) placeLive(cn.getPackageName());
            }
        }
    }

    /** Applies the name, then re-checks the DB row and live label and re-applies if overwritten. */
    private void rename(int folderId, String name, int attempt) {
        Object folder = bridge.findFolderById(folderId);
        if (folder == null) return;
        String dbTitle = bridge.dbTitle(folderId);
        String label = bridge.label(folder).trim();
        if (attempt > 0 && name.equals(dbTitle) && name.equals(label)) {
            return;
        }
        if (attempt >= RENAME_TRIES) {
            log("폴더 이름 '" + name + "' 붙이기 실패 (지금: '" + label + "')");
            return;
        }
        bridge.renameFolder(folder, name);
        main.postDelayed(() -> rename(folderId, name, attempt + 1), RENAME_VERIFY_MS);
    }

    /** DB write hook (any thread). Feeds the tracker with where apps-screen apps now live. */
    public void onItemWritten(Object itemData) {
        main.post(() -> {
            try {
                if (!"APP".equals(String.valueOf(XposedHelpers.callMethod(itemData, "getType")))) return;
                String component = (String) XposedHelpers.callMethod(itemData, "getComponent");
                ComponentName cn = component == null ? null : ComponentName.unflattenFromString(component);
                if (cn == null) return;
                int itemId = (int) XposedHelpers.callMethod(itemData, "getId");
                String containerType = String.valueOf(XposedHelpers.callMethod(itemData, "getContainerType"));
                int containerId = (int) XposedHelpers.callMethod(itemData, "getContainerId");

                int inFolder;
                if ("FOLDER".equals(containerType)) {
                    if (!isApplistFolder(containerId)) return;
                    inFolder = containerId;
                } else if ("ITEM_GROUP".equals(containerType)) {
                    if (!isApplistPage(containerId)) return;
                    inFolder = -1;
                } else {
                    return;
                }
                String change = tracker.onItemUpdated(cn.getPackageName(), itemId, inFolder);
                if (change != null) queueMove(change.replace(cn.getPackageName(), app(cn.getPackageName())));
            } catch (Throwable t) {
                log("앱 위치 추적 실패: " + t);
            }
        });
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Move reports from one burst of launcher writes go out as one summary line, not one line each. */
    private final List<String> pendingMoves = new ArrayList<>();
    private final Runnable flushMoves = () -> {
        int n = pendingMoves.size();
        if (n == 0) return;
        String shown = String.join(" · ", pendingMoves.subList(0, Math.min(3, n)));
        log("앱 위치 변경 " + n + "건: " + shown + (n > 3 ? " 외 " + (n - 3) + "건" : ""));
        pendingMoves.clear();
    };

    private void queueMove(String change) {
        pendingMoves.add(change);
        main.removeCallbacks(flushMoves);
        main.postDelayed(flushMoves, 1500);
    }

    /**
     * "지금 백업": takes a snapshot, unless the newest one already holds this exact layout, in which
     * case it says so instead of making a duplicate.
     */
    private void backupNow() {
        if (off(Diag.F_BACKUP)) {
            report("백업 기능이 꺼져 있습니다");
            return;
        }
        new Thread(() -> {
            try {
                String latest = backup.latest();
                if (latest != null && backup.file(latest, "OneUI.db").exists()) {
                    String same = planner.layoutOf(backup.workingCopy(latest)).toString();
                    if (same.equals(planner.currentLayout().toString())) {
                        report("이미 백업되어 있어요 (" + latest + " 시점과 같은 상태)");
                        return;
                    }
                }
                report("백업을 만들었어요: " + snapshot());
            } catch (Throwable t) {
                Diag.failed(Diag.F_BACKUP, "backup", t);
                report("백업 실패: " + t.getMessage());
            }
        }, "AutoClassify-backup").start();
    }

    /** A backup of the layout, with the current rules and AI instructions beside it. */
    private String snapshot() throws java.io.IOException {
        return backup.snapshot(instructionsLoader.get());
    }

    private void report(String msg) {
        log(msg);
        reply(Config.ACTION_RESULT, msg);
    }

    private void reply(String action, String payload) {
        context.sendBroadcast(new Intent(action)
                .setPackage(Config.MODULE_PKG)
                .putExtra(Config.EXTRA_MESSAGE, payload));
    }

    private boolean isApplistPage(int groupId) {
        Object group = XposedHelpers.callMethod(dataSource, "getHoneyGroupData", groupId);
        if (group == null || !"page".equals(XposedHelpers.callMethod(group, "getType"))) return false;
        Object parent = XposedHelpers.callMethod(dataSource, "getHoneyGroupData",
                (int) XposedHelpers.callMethod(group, "getContainerId"));
        return parent != null && "applist".equals(XposedHelpers.callMethod(parent, "getType"));
    }

    private boolean isApplistFolder(int folderId) {
        Object folder = XposedHelpers.callMethod(dataSource, "getHoneyData", folderId);
        return folder != null
                && "ITEM_GROUP".equals(String.valueOf(XposedHelpers.callMethod(folder, "getContainerType")))
                && isApplistPage((int) XposedHelpers.callMethod(folder, "getContainerId"));
    }

    private ApplicationInfo appInfo(String pkg) {
        try {
            return context.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void dumpFolders() {
        if (bridge == null) {
            log("apps screen not ready");
            return;
        }
        for (Object item : bridge.topLevelItems()) {
            if (bridge.isFolder(item)) {
                int id = bridge.id(item);
                log("folder #" + id + " label='" + bridge.label(item) + "' db='" + bridge.dbTitle(id) + "'");
            }
        }
    }

    /** App name for the log (package name when unknown). */
    private String app(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) + "";
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private String apps(java.util.Collection<String> pkgs) {
        List<String> names = new ArrayList<>();
        for (String p : pkgs) names.add(app(p));
        return String.join(", ", names);
    }

    private static void log(String msg) {
        ModuleMain.log(msg);
    }
}
