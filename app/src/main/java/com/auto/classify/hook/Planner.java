package com.auto.classify.hook;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import com.auto.classify.core.Directives;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole-apps-screen reorganisation done directly on the launcher's layout DB (OneUI.db).
 * Unlike the live event path this can also move apps that already sit in folders.
 *
 * The plan is simulated on an in-memory copy of the layout, so the preview shows exactly what
 * will be written: apps filed into folders, new folders at the slot of their first loose
 * member, emptied folders removed, and every page refilled in order to the launcher's grid
 * size (cols x rows) with leftover pages deleted. Applying writes it all in one transaction;
 * the launcher must then restart to reload its in-memory model.
 */
final class Planner {

    /** Folder name meaning "take the app out of its folder". */
    static final String LOOSE = "(폴더밖)";

    /** What the classifier decided for a batch, plus how to arrange the result. */
    static final class Targets {
        /** pkg -> {folder or LOOSE, reason, page as text or null}; absent = leave the app alone. */
        final Map<String, String[]> byPkg = new HashMap<>();
        Directives directives = new Directives();
        /** Sort keys, filled only when a directive asks for that order. */
        final Map<String, Float> colorKey = new HashMap<>();
        final Map<String, Long> installed = new HashMap<>();
        /** Folder color requested by the rule that matched each app (ARGB; FolderColors.DEFAULT = reset). */
        final Map<String, Integer> folderColor = new HashMap<>();
        /** Icon main color per app, for "!폴더색 아이콘". */
        final Map<String, Integer> mainColor = new HashMap<>();
        /** Folder names the module creates; such folders are cleaned up when they go stale. */
        final java.util.Set<String> autoNames = new java.util.HashSet<>();
        boolean cleanStale = true;
        /** Config.UNCLASSIFIED_* and its folder name / page. */
        String unclassified = com.auto.classify.core.Config.UNCLASSIFIED_KEEP;
        String unclassifiedFolder = "기타";
        int unclassifiedPage = 1;
        final java.util.Set<String> excluded = new java.util.HashSet<>();
    }

    interface Rule {
        /** Classifies a batch at once (some methods, e.g. color similarity, need all apps together). */
        Targets targets(List<String> pkgs, List<String> labels);
    }

    static final class Row {
        int id;
        String type;
        String title;
        String component;
        int containerType;
        int containerId;
        int rank;
        int hidden;
        int color;
        int options;
        String pkg;
        String label;
    }

    static final class Move {
        Row app;
        String from; // folder title or null for loose
        String to;
        String reason;
        int page;
    }

    static final class Plan {
        final List<Move> moves = new ArrayList<>();
        final List<String> created = new ArrayList<>();
        final List<String> emptied = new ArrayList<>();
        /** Folder title -> 1-based page requested by a rule. */
        final Map<String, Integer> pins = new LinkedHashMap<>();
        /** Loose app package -> 1-based page requested by a (폴더밖)@N rule. */
        final Map<String, Integer> loosePins = new LinkedHashMap<>();
        /** Folder title -> color set by a rule (wins over the !폴더색 directive). */
        final Map<String, Integer> ruleColors = new LinkedHashMap<>();
        Targets targets;
        JSONObject before;
        JSONObject after;
        /** True when the layout changes at all (moves, page pins, or refilling sparse pages). */
        boolean changed;

        boolean isEmpty() {
            return !changed;
        }

        /** One readable line for the log: "앱 3개 이동 (카카오톡 → 소통, …) · 새 폴더 AI". */
        String summary() {
            if (!changed) return "바꿀 것 없음";
            StringBuilder sb = new StringBuilder("앱 ").append(moves.size()).append("개 이동");
            List<String> some = new ArrayList<>();
            for (int i = 0; i < Math.min(5, moves.size()); i++) {
                Move mv = moves.get(i);
                some.add(mv.app.label + " → " + (LOOSE.equals(mv.to) ? "폴더 밖" : mv.to));
            }
            if (!some.isEmpty()) sb.append(" (").append(String.join(", ", some)).append(moves.size() > 5 ? ", …)" : ")");
            if (!created.isEmpty()) sb.append(" · 새 폴더 ").append(String.join(", ", created));
            if (!emptied.isEmpty()) sb.append(" · 비는 폴더 ").append(String.join(", ", emptied));
            return sb.toString();
        }

        JSONObject toJson() throws JSONException {
            JSONArray m = new JSONArray();
            for (Move mv : moves) {
                m.put(new JSONObject()
                        .put("label", mv.app.label)
                        .put("pkg", mv.app.pkg)
                        .put("from", mv.from == null ? JSONObject.NULL : mv.from)
                        .put("to", mv.to)
                        .put("reason", mv.reason == null ? "" : mv.reason));
            }
            return new JSONObject()
                    .put("moves", m)
                    .put("created", new JSONArray(created))
                    .put("emptied", new JSONArray(emptied))
                    .put("pins", new JSONObject(pins))
                    .put("ruleColors", new JSONObject(ruleColors))
                    .put("changed", changed)
                    .put("before", before)
                    .put("after", after);
        }
    }

    /** Columns the planner reads or writes; checked at startup. */
    private static final String[] ITEM_COLUMNS = {"id", "type", "title", "component", "container_type",
            "container_id", "rank", "hidden"};
    private static final String[] GROUP_COLUMNS = {"id", "type", "container_id", "rank", "display_type"};
    private static final int MAIN_DISPLAY = 0;

    private final Context context;
    private final File dbFile;
    private int cols = 4;
    private int rows = 6;
    /** Live {cols, rows} of the apps screen; read on every load so grid changes are picked up. */
    private java.util.function.Supplier<int[]> gridSource;

    // Model loaded by load()
    private int applistId = -1;
    private final List<Integer> pages = new ArrayList<>();
    private final Map<Integer, List<Row>> top = new LinkedHashMap<>();
    private final Map<Integer, List<Row>> children = new HashMap<>();
    private final Map<Integer, Row> folders = new LinkedHashMap<>();
    private int maxId;
    private int maxGroupId;

    // Simulation results
    private final Map<String, Row> created = new LinkedHashMap<>();
    private final List<Row> dropped = new ArrayList<>();
    private final List<Integer> newPages = new ArrayList<>();
    private final List<Integer> droppedPages = new ArrayList<>();

    Planner(Context context) {
        this.context = context;
        this.dbFile = context.getDatabasePath("OneUI.db");
    }

    void setGridSource(java.util.function.Supplier<int[]> source) {
        this.gridSource = source;
    }

    /** Grid of the apps screen as reported by the launcher; used to refill pages. */
    void setGrid(int cols, int rows) {
        if (cols > 0 && rows > 0) {
            this.cols = cols;
            this.rows = rows;
        }
    }

    /** @return null when the DB looks like what this planner understands, else the reason. */
    synchronized String checkSchema() {
        if (!dbFile.exists()) return "OneUI.db 없음 (홈 화면만 쓰는 모드일 수 있음)";
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READONLY);
        try {
            String miss = missing(db, "item", ITEM_COLUMNS);
            if (miss == null) miss = missing(db, "item_group", GROUP_COLUMNS);
            if (miss != null) return miss;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM item_group WHERE type='applist'", null)) {
                if (!c.moveToFirst() || c.getInt(0) == 0) return "앱스 화면(applist) 그룹 없음";
            }
            return null;
        } finally {
            db.close();
        }
    }

    private static String missing(SQLiteDatabase db, String table, String[] cols) {
        Set<String> have = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) have.add(c.getString(1));
        }
        if (have.isEmpty()) return "테이블 " + table + " 없음";
        for (String col : cols) if (!have.contains(col)) return table + "." + col + " 컬럼 없음";
        return null;
    }

    synchronized Plan plan(Rule rule, boolean createFolders, int minApps) throws JSONException {
        SQLiteDatabase db = open(dbFile);
        try {
            load(db);
            Plan plan = new Plan();
            plan.before = layout();
            compute(rule, createFolders, minApps, plan);
            simulate(plan);
            plan.after = layout();
            plan.changed = !plan.before.toString().equals(plan.after.toString());
            return plan;
        } finally {
            db.close();
        }
    }

    /** Recomputes the plan against the current DB and writes it. @return the plan applied */
    synchronized Plan apply(Rule rule, boolean createFolders, int minApps) {
        SQLiteDatabase db = open(dbFile);
        try {
            load(db);
            Plan plan = new Plan();
            String before = layout().toString();
            compute(rule, createFolders, minApps, plan);
            simulate(plan);
            plan.changed = !before.equals(layout().toString());
            if (plan.changed) write(db);
            return plan;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        } finally {
            db.close();
        }
    }

    /**
     * Writes a layout edited by hand in the preview (pages of apps and folders, by item id).
     * Folders with ids the DB doesn't have are created; folders left out are removed; apps the
     * edit doesn't mention keep going at the end of the last page.
     * @return number of apps whose place changed
     */
    synchronized int applyLayout(JSONObject edited) throws JSONException {
        SQLiteDatabase db = open(dbFile);
        try {
            load(db);
            Map<Integer, Row> apps = new HashMap<>();
            Map<Integer, String> before = new HashMap<>();
            for (int page : pages) {
                for (Row r : top.get(page)) {
                    if ("app".equals(r.type)) {
                        apps.put(r.id, r);
                        before.put(r.id, "p" + page + ":" + top.get(page).indexOf(r));
                    } else if ("folder".equals(r.type)) {
                        List<Row> kids = children.getOrDefault(r.id, new ArrayList<>());
                        for (Row c : kids) {
                            if ("app".equals(c.type)) {
                                apps.put(c.id, c);
                                before.put(c.id, "f" + r.id + ":" + kids.indexOf(c));
                            }
                        }
                    }
                }
            }
            Map<Integer, Row> oldFolders = new LinkedHashMap<>(folders);
            java.util.Set<Integer> used = new java.util.HashSet<>();
            List<List<Row>> layout = new ArrayList<>();
            Map<Integer, List<Row>> kids = new HashMap<>();
            JSONArray ps = edited.getJSONArray("pages");
            for (int p = 0; p < ps.length(); p++) {
                JSONArray items = ps.getJSONArray(p);
                List<Row> page = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    if ("folder".equals(it.optString("type"))) {
                        JSONArray fa = it.optJSONArray("apps");
                        List<Row> members = new ArrayList<>();
                        for (int k = 0; fa != null && k < fa.length(); k++) {
                            Row a = apps.get(fa.getJSONObject(k).optInt("id", -1));
                            if (a != null && used.add(a.id)) members.add(a);
                        }
                        if (members.isEmpty()) continue;
                        Row f = oldFolders.get(it.optInt("id", -1));
                        if (f == null) {
                            f = new Row();
                            f.id = ++maxId;
                            f.type = "folder";
                            f.title = it.optString("title", "폴더");
                            f.containerType = 0;
                            folders.put(f.id, f);
                            created.put(f.title + "#" + f.id, f);
                        } else if (it.has("title")) {
                            f.title = it.optString("title");
                        }
                        page.add(f);
                        kids.put(f.id, members);
                    } else {
                        Row a = apps.get(it.optInt("id", -1));
                        if (a != null && used.add(a.id)) page.add(a);
                    }
                }
                if (!page.isEmpty()) layout.add(page);
            }
            // Anything the edit lost goes to the end rather than disappearing.
            List<Row> leftover = new ArrayList<>();
            for (Row a : apps.values()) if (!used.contains(a.id)) leftover.add(a);
            int capacity = Math.max(1, cols * rows);
            for (Row a : leftover) {
                if (layout.isEmpty() || layout.get(layout.size() - 1).size() >= capacity) layout.add(new ArrayList<>());
                layout.get(layout.size() - 1).add(a);
            }
            if (layout.isEmpty()) layout.add(new ArrayList<>());

            for (Row f : oldFolders.values()) {
                if (!kids.containsKey(f.id)) {
                    dropped.add(f);
                    folders.remove(f.id);
                }
            }
            children.keySet().retainAll(java.util.Collections.emptySet());
            children.putAll(kids);
            while (pages.size() < layout.size()) {
                int id = ++maxGroupId;
                pages.add(id);
                top.put(id, new ArrayList<>());
                newPages.add(id);
            }
            while (pages.size() > layout.size()) {
                int id = pages.remove(pages.size() - 1);
                top.remove(id);
                droppedPages.add(id);
            }
            int changed = 0;
            for (int i = 0; i < layout.size(); i++) {
                int page = pages.get(i);
                top.get(page).clear();
                for (Row r : layout.get(i)) {
                    r.containerId = page;
                    top.get(page).add(r);
                    if ("app".equals(r.type) && !("p" + page + ":" + (top.get(page).size() - 1)).equals(before.get(r.id))) changed++;
                }
            }
            for (Map.Entry<Integer, List<Row>> e : kids.entrySet()) {
                for (int i = 0; i < e.getValue().size(); i++) {
                    Row a = e.getValue().get(i);
                    if (!("f" + e.getKey() + ":" + i).equals(before.get(a.id))) changed++;
                }
            }
            write(db);
            // Titles of kept folders may have been edited.
            for (Row f : folders.values()) {
                if (created.containsValue(f)) continue;
                ContentValues v = new ContentValues();
                v.put("title", f.title);
                db.update("item", v, "id=?", new String[]{String.valueOf(f.id)});
            }
            return changed;
        } finally {
            db.close();
        }
    }

    /** Current apps-screen layout. */
    synchronized JSONObject currentLayout() throws JSONException {
        return layoutOf(dbFile);
    }

    /** Full apps-screen layout of any OneUI.db copy, for backup previews. */
    synchronized JSONObject layoutOf(File db) throws JSONException {
        SQLiteDatabase d = open(db);
        try {
            load(d);
            return layout();
        } finally {
            d.close();
        }
    }

    // ---- model ----------------------------------------------------------------------------

    private static SQLiteDatabase open(File f) {
        return SQLiteDatabase.openDatabase(f.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
    }

    private void load(SQLiteDatabase db) {
        int[] grid = gridSource == null ? null : gridSource.get();
        if (grid != null) setGrid(grid[0], grid[1]);
        pages.clear();
        top.clear();
        children.clear();
        folders.clear();
        created.clear();
        dropped.clear();
        newPages.clear();
        droppedPages.clear();

        applistId = -1;
        try (Cursor c = db.rawQuery("SELECT id FROM item_group WHERE type='applist' AND display_type=? ORDER BY id LIMIT 1",
                new String[]{String.valueOf(MAIN_DISPLAY)})) {
            if (c.moveToFirst()) applistId = c.getInt(0);
        }
        try (Cursor c = db.rawQuery("SELECT id FROM item_group WHERE type='page' AND container_id=? AND display_type=? ORDER BY rank",
                new String[]{String.valueOf(applistId), String.valueOf(MAIN_DISPLAY)})) {
            while (c.moveToNext()) {
                pages.add(c.getInt(0));
                top.put(c.getInt(0), new ArrayList<>());
            }
        }
        try (Cursor c = db.rawQuery("SELECT MAX(id) FROM item", null)) {
            maxId = c.moveToFirst() ? c.getInt(0) : 0;
        }
        try (Cursor c = db.rawQuery("SELECT MAX(id) FROM item_group", null)) {
            maxGroupId = c.moveToFirst() ? c.getInt(0) : 0;
        }
        List<Row> all = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id,type,title,component,container_type,container_id,rank,hidden,color,options"
                + " FROM item ORDER BY rank, id", null)) {
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getInt(0);
                r.type = c.getString(1);
                r.title = c.getString(2);
                r.component = c.getString(3);
                r.containerType = c.getInt(4);
                r.containerId = c.getInt(5);
                r.rank = c.getInt(6);
                r.hidden = c.getInt(7);
                r.color = c.getInt(8);
                r.options = c.getInt(9);
                all.add(r);
            }
        }
        for (Row r : all) {
            if (r.containerType == 0 && top.containsKey(r.containerId)) {
                top.get(r.containerId).add(r);
                if ("folder".equals(r.type)) folders.put(r.id, r);
            }
        }
        for (Row r : all) {
            if (r.containerType == 1 && folders.containsKey(r.containerId)) {
                children.computeIfAbsent(r.containerId, k -> new ArrayList<>()).add(r);
            }
        }
        PackageManager pm = context.getPackageManager();
        for (Row r : all) {
            if (!"app".equals(r.type) || r.component == null) continue;
            ComponentName cn = ComponentName.unflattenFromString(r.component);
            if (cn == null) continue;
            r.pkg = cn.getPackageName();
            try {
                r.label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(r.pkg, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                r.label = r.title != null ? r.title : r.pkg;
            }
        }
    }

    private void compute(Rule rule, boolean createFolders, int minApps, Plan plan) {
        Map<String, Row> byTitle = foldersByTitle();
        // Every app on the apps screen with the folder it sits in (null = loose).
        List<Row[]> apps = new ArrayList<>();
        for (int page : pages) {
            for (Row r : top.get(page)) {
                if ("app".equals(r.type)) apps.add(new Row[]{r, null});
                else if ("folder".equals(r.type)) {
                    for (Row c : children.getOrDefault(r.id, new ArrayList<>())) {
                        if ("app".equals(c.type)) apps.add(new Row[]{c, r});
                    }
                }
            }
        }
        List<String> pkgs = new ArrayList<>(), labels = new ArrayList<>();
        for (Row[] a : apps) {
            if (a[0].pkg == null || a[0].hidden != 0) continue;
            pkgs.add(a[0].pkg);
            labels.add(a[0].label);
        }
        Targets t = rule.targets(pkgs, labels);
        plan.targets = t;
        currentTargets = t;
        currentRuleColors = plan.ruleColors;
        if (t.directives.minApps > 0) minApps = t.directives.minApps;
        Map<String, List<Move>> wanted = new LinkedHashMap<>();
        for (Row[] a : apps) consider(t.byPkg, a[0], a[1], wanted);

        // Apps nothing claims (setting), before folder-less moves are sorted out below.
        unclassified(apps, t, wanted);

        // Taking apps out of folders: only apps that are in a folder actually move.
        List<Move> out = wanted.remove(LOOSE);
        if (out != null) {
            for (Move mv : out) {
                if (mv.page > 0) plan.loosePins.put(mv.app.pkg, mv.page);
                if (mv.app.containerType == 1) plan.moves.add(mv);
            }
        }

        // Folders that exist after this plan.
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Map.Entry<String, List<Move>> e : wanted.entrySet()) {
            String name = e.getKey();
            List<Move> moves = e.getValue();
            for (Move mv : moves) {
                if (mv.page > 0) {
                    plan.pins.putIfAbsent(name, mv.page);
                    break;
                }
            }
            Row existing = byTitle.get(name);
            if (existing == null) {
                if (!createFolders || moves.size() < Math.max(1, minApps)) continue;
                plan.created.add(name);
            }
            for (Move mv : moves) {
                boolean alreadyThere = existing != null && mv.app.containerType == 1
                        && mv.app.containerId == existing.id;
                if (!alreadyThere) plan.moves.add(mv);
            }
            live.add(name);
        }

        // Old automatic folders (투톤, 파랑 2 ...) that the current settings don't use: whatever
        // would stay in them (no target, or a target too small to get a folder) comes out, so the
        // folder ends up empty and is removed.
        if (t.cleanStale) {
            java.util.Set<Integer> moving = new java.util.HashSet<>();
            for (Move mv : plan.moves) moving.add(mv.app.id);
            for (Row[] a : apps) {
                Row app = a[0], folder = a[1];
                if (folder == null || app.pkg == null || app.hidden != 0 || moving.contains(app.id)) continue;
                String title = folder.title == null ? "" : folder.title.trim();
                if (live.contains(title) || !t.autoNames.contains(title)) continue;
                Move mv = new Move();
                mv.app = app;
                mv.from = title;
                mv.to = LOOSE;
                mv.reason = "이전 자동 폴더 정리 (지금 기준으로 맞는 폴더 없음)";
                plan.moves.add(mv);
            }
        }
    }

    /**
     * Apps nothing claims, per the "분류 안 된 앱" setting. Only loose apps and apps in automatic
     * folders the plan no longer uses; apps in the user's own folders stay put.
     */
    private void unclassified(List<Row[]> apps, Targets t, Map<String, List<Move>> wanted) {
        String mode = t.unclassified;
        if (com.auto.classify.core.Config.UNCLASSIFIED_KEEP.equals(mode)) return;
        boolean toFolder = com.auto.classify.core.Config.UNCLASSIFIED_FOLDER.equals(mode)
                && t.unclassifiedFolder != null && !t.unclassifiedFolder.trim().isEmpty();
        for (Row[] a : apps) {
            Row app = a[0], folder = a[1];
            if (app.pkg == null || app.hidden != 0 || t.excluded.contains(app.pkg)) continue;
            String[] tg = t.byPkg.get(app.pkg);
            if (tg != null && tg[0] != null) continue;
            String title = folder == null || folder.title == null ? null : folder.title.trim();
            if (title != null && (!t.autoNames.contains(title) || wanted.containsKey(title))) continue;
            Move mv = new Move();
            mv.app = app;
            mv.from = title;
            if (toFolder) {
                mv.to = t.unclassifiedFolder.trim();
                if (mv.to.equals(title)) continue;
                mv.reason = "분류 안 됨 → '" + mv.to + "' 폴더";
            } else {
                mv.to = LOOSE;
                boolean page = com.auto.classify.core.Config.UNCLASSIFIED_PAGE.equals(mode);
                mv.page = page ? Math.max(1, t.unclassifiedPage) : 0;
                mv.reason = page ? "분류 안 됨 → " + mv.page + "페이지" : "분류 안 됨 → 규칙 없는 페이지";
            }
            wanted.computeIfAbsent(mv.to, k -> new ArrayList<>()).add(mv);
        }
    }

    private void consider(Map<String, String[]> targets, Row app, Row folder, Map<String, List<Move>> wanted) {
        if (app.pkg == null || app.hidden != 0) return;
        String[] t = targets.get(app.pkg);
        if (t == null || t[0] == null) return;
        String to = t[0];
        Move mv = new Move();
        mv.app = app;
        mv.from = folder == null || folder.title == null ? null : folder.title.trim();
        mv.to = to;
        mv.reason = t[1];
        Integer ruleColor = currentTargets.folderColor.get(app.pkg);
        if (ruleColor != null && !LOOSE.equals(to)) currentRuleColors.putIfAbsent(to, ruleColor);
        try {
            mv.page = t.length > 2 && t[2] != null ? Integer.parseInt(t[2]) : 0;
        } catch (NumberFormatException ignored) {
            mv.page = 0;
        }
        wanted.computeIfAbsent(to, k -> new ArrayList<>()).add(mv);
    }

    // Set by compute() for consider(); planner methods are synchronized.
    private Targets currentTargets = new Targets();
    private Map<String, Integer> currentRuleColors = new LinkedHashMap<>();

    private Map<String, Row> foldersByTitle() {
        Map<String, Row> byTitle = new HashMap<>();
        for (Row f : folders.values()) if (f.title != null) byTitle.putIfAbsent(f.title.trim(), f);
        return byTitle;
    }

    /** Applies the plan to the in-memory model (no DB access). */
    private void simulate(Plan plan) {
        Map<String, Row> byTitle = foldersByTitle();

        // Where each new folder goes: the slot of its first loose member.
        Map<String, Row> anchorOf = new HashMap<>();
        for (Move mv : plan.moves) {
            if (byTitle.containsKey(mv.to) || anchorOf.containsKey(mv.to) || mv.app.containerType != 0) continue;
            if (plan.created.contains(mv.to)) anchorOf.put(mv.to, mv.app);
        }

        int nextId = maxId;
        for (String name : plan.created) {
            Row f = new Row();
            f.id = ++nextId;
            f.type = "folder";
            f.title = name;
            f.containerType = 0;
            created.put(name, f);
            byTitle.put(name, f);
            folders.put(f.id, f);
            children.put(f.id, new ArrayList<>());
            Row anchor = anchorOf.get(name);
            if (anchor != null) {
                List<Row> l = top.get(anchor.containerId);
                f.containerId = anchor.containerId;
                l.add(l.indexOf(anchor), f);
            } else {
                int last = pages.get(pages.size() - 1);
                f.containerId = last;
                top.get(last).add(f);
            }
        }

        for (Move mv : plan.moves) {
            Row app = mv.app;
            if (LOOSE.equals(mv.to)) {
                // Out of the folder, right after it on the same page.
                Row folder = folders.get(app.containerId);
                children.get(app.containerId).remove(app);
                List<Row> l = top.get(folder.containerId);
                l.add(l.indexOf(folder) + 1, app);
                app.containerType = 0;
                app.containerId = folder.containerId;
                continue;
            }
            if (app.containerType == 0) top.get(app.containerId).remove(app);
            else children.get(app.containerId).remove(app);
            Row target = byTitle.get(mv.to);
            app.containerType = 1;
            app.containerId = target.id;
            children.get(target.id).add(app);
        }

        for (Row f : new ArrayList<>(folders.values())) {
            if (created.containsValue(f)) continue;
            if (children.getOrDefault(f.id, new ArrayList<>()).isEmpty()) {
                top.get(f.containerId).remove(f);
                dropped.add(f);
                folders.remove(f.id);
                if (f.title != null) plan.emptied.add(f.title);
            }
        }

        arrange(plan.targets);
        colorFolders(plan);
        reflowPages(plan.pins, plan.loosePins);
    }

    /** Rule colors first, then the !폴더색 directive; folders nobody decides keep their color. */
    private void colorFolders(Plan plan) {
        Targets t = plan.targets;
        String mode = t == null ? Directives.KEEP : t.directives.folderColor;
        for (Row f : folders.values()) {
            String title = f.title == null ? "" : f.title.trim();
            Integer c = plan.ruleColors.get(title);
            if (c == null) {
                switch (mode) {
                    case Directives.RESET:
                        c = com.auto.classify.core.FolderColors.DEFAULT;
                        break;
                    case Directives.CATEGORY:
                        c = com.auto.classify.core.FolderColors.forCategory(title);
                        break;
                    case Directives.ICON:
                        c = iconColor(f, t);
                        break;
                    default:
                }
            }
            if (c == null) continue;
            f.color = c;
            f.options = c == com.auto.classify.core.FolderColors.DEFAULT
                    ? f.options & ~com.auto.classify.core.FolderColors.CUSTOM_FLAG
                    : f.options | com.auto.classify.core.FolderColors.CUSTOM_FLAG;
        }
    }

    /** The color group most of the folder's apps fall into, as that group's color. */
    private Integer iconColor(Row folder, Targets t) {
        Map<String, Integer> votes = new HashMap<>();
        for (Row c : children.getOrDefault(folder.id, new ArrayList<>())) {
            Integer main = t.mainColor.get(c.pkg);
            if (main == null) continue;
            String name = com.auto.classify.core.ColorEngine.hueName(main);
            if (com.auto.classify.core.ColorEngine.ACHROMATIC.equals(name)) name = "회색";
            votes.merge(name, 1, Integer::sum);
        }
        String best = null;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (best == null || e.getValue() > votes.get(best)) best = e.getKey();
        }
        return best == null ? null : com.auto.classify.core.FolderColors.nameColor(best);
    }

    /** Applies the sort/placement directives to folder contents and the top-level order. */
    private void arrange(Targets t) {
        if (t == null) return;
        Directives d = t.directives;
        Collator collator = Collator.getInstance(Locale.KOREAN);
        Comparator<Row> byName = (a, b) -> collator.compare(name(a), name(b));

        Comparator<Row> inside = appOrder(d.insideSort, t, byName);
        if (inside != null) {
            for (Row f : folders.values()) {
                List<Row> l = children.get(f.id);
                if (l != null) l.sort(inside);
            }
        }

        List<Row> flat = new ArrayList<>();
        for (int page : pages) flat.addAll(top.get(page));
        List<Row> fs = new ArrayList<>(), as = new ArrayList<>();
        for (Row r : flat) ("folder".equals(r.type) ? fs : as).add(r);

        Comparator<Row> loose = appOrder(d.looseSort, t, byName);
        if (loose != null) as.sort(loose);
        if (Directives.NAME.equals(d.folderSort)) fs.sort(byName);
        else if (Directives.COLOR.equals(d.folderSort)) {
            // A folder sorts by the median color of its apps (rainbow order, grays last).
            Map<Integer, Float> key = new HashMap<>();
            for (Row f : fs) {
                List<Float> ks = new ArrayList<>();
                for (Row c : children.getOrDefault(f.id, new ArrayList<>())) ks.add(t.colorKey.getOrDefault(c.pkg, Float.MAX_VALUE));
                Collections.sort(ks);
                key.put(f.id, ks.isEmpty() ? Float.MAX_VALUE : ks.get(ks.size() / 2));
            }
            fs.sort(Comparator.comparing((Row r) -> key.get(r.id)).thenComparing(byName));
        } else if (Directives.SIZE.equals(d.folderSort)) {
            fs.sort((a, b) -> Integer.compare(children.getOrDefault(b.id, new ArrayList<>()).size(),
                    children.getOrDefault(a.id, new ArrayList<>()).size()));
        }

        List<Row> ordered = new ArrayList<>();
        if (Directives.FOLDERS_FIRST.equals(d.placement)) {
            ordered.addAll(fs);
            ordered.addAll(as);
        } else if (Directives.APPS_FIRST.equals(d.placement)) {
            ordered.addAll(as);
            ordered.addAll(fs);
        } else {
            // Keep which slots hold folders and which hold apps; only reorder within each kind.
            int fi = 0, ai = 0;
            for (Row r : flat) ordered.add("folder".equals(r.type) ? fs.get(fi++) : as.get(ai++));
        }
        // Put the result back on the existing pages in order; reflowPages() repaginates.
        for (int page : pages) top.get(page).clear();
        top.get(pages.get(0)).addAll(ordered);
        for (Row r : ordered) r.containerId = pages.get(0);
    }

    private Comparator<Row> appOrder(String sort, Targets t, Comparator<Row> byName) {
        switch (sort) {
            case Directives.NAME:
                return byName;
            case Directives.COLOR:
                return Comparator.comparing((Row r) -> t.colorKey.getOrDefault(r.pkg, Float.MAX_VALUE))
                        .thenComparing(byName);
            case Directives.INSTALLED:
                return Comparator.comparing((Row r) -> t.installed.getOrDefault(r.pkg, Long.MAX_VALUE))
                        .thenComparing(byName);
            default:
                return null;
        }
    }

    private static String name(Row r) {
        if ("folder".equals(r.type)) return r.title == null ? "" : r.title;
        return r.label == null ? "" : r.label;
    }

    /**
     * Refills pages in order to cols*rows items each; adds or drops page groups as needed.
     * Folders pinned to a page by a rule get that page to themselves; everything else fills the
     * remaining pages in its current order. Pages that would end up empty are skipped.
     */
    private void reflowPages(Map<String, Integer> pins, Map<String, Integer> loosePins) {
        int capacity = Math.max(1, cols * rows);
        java.util.TreeMap<Integer, List<Row>> pinned = new java.util.TreeMap<>();
        List<Row> loose = new ArrayList<>();
        for (int page : pages) {
            for (Row r : top.get(page)) {
                Integer pin = "folder".equals(r.type) && r.title != null ? pins.get(r.title.trim())
                        : r.pkg != null ? loosePins.get(r.pkg) : null;
                if (pin != null) pinned.computeIfAbsent(pin, k -> new ArrayList<>()).add(r);
                else loose.add(r);
            }
        }
        List<List<Row>> layout = new ArrayList<>();
        int next = 0;
        for (int p = 1; next < loose.size() || pinned.ceilingKey(p) != null; p++) {
            List<Row> mine = pinned.get(p);
            if (mine != null) {
                for (int i = 0; i < mine.size(); i += capacity) {
                    layout.add(new ArrayList<>(mine.subList(i, Math.min(mine.size(), i + capacity))));
                }
            } else if (next < loose.size()) {
                int end = Math.min(loose.size(), next + capacity);
                layout.add(new ArrayList<>(loose.subList(next, end)));
                next = end;
            }
        }
        if (layout.isEmpty()) layout.add(new ArrayList<>());
        List<Row> flat = new ArrayList<>();
        for (List<Row> l : layout) flat.addAll(l);
        int needed = layout.size();

        while (pages.size() < needed) {
            int id = ++maxGroupId;
            pages.add(id);
            top.put(id, new ArrayList<>());
            newPages.add(id);
        }
        while (pages.size() > needed) {
            int id = pages.remove(pages.size() - 1);
            top.remove(id);
            droppedPages.add(id);
        }
        for (int page : pages) top.get(page).clear();
        for (int i = 0; i < layout.size(); i++) {
            int page = pages.get(i);
            for (Row r : layout.get(i)) {
                r.containerId = page;
                top.get(page).add(r);
            }
        }
    }

    private JSONObject layout() throws JSONException {
        JSONArray ps = new JSONArray();
        for (int page : pages) {
            JSONArray items = new JSONArray();
            for (Row r : top.get(page)) {
                if ("folder".equals(r.type)) {
                    JSONArray apps = new JSONArray();
                    for (Row c : children.getOrDefault(r.id, new ArrayList<>())) {
                        apps.put(new JSONObject().put("id", c.id).put("pkg", c.pkg).put("label", c.label));
                    }
                    JSONObject fo = new JSONObject().put("type", "folder").put("id", r.id)
                            .put("title", r.title == null ? "" : r.title).put("apps", apps);
                    if (r.color != com.auto.classify.core.FolderColors.DEFAULT) fo.put("color", r.color);
                    items.put(fo);
                } else if ("app".equals(r.type)) {
                    items.put(new JSONObject().put("type", "app").put("id", r.id).put("pkg", r.pkg).put("label", r.label));
                }
            }
            ps.put(items);
        }
        return new JSONObject().put("cols", cols).put("rows", rows).put("pages", ps);
    }

    // ---- write ----------------------------------------------------------------------------

    private void write(SQLiteDatabase db) {
        Row template = null;
        for (Row f : folders.values()) {
            if (!created.containsValue(f)) {
                template = f;
                break;
            }
        }
        db.beginTransaction();
        try {
            for (int id : newPages) insertPage(db, id);
            for (int id : droppedPages) db.delete("item_group", "id=?", new String[]{String.valueOf(id)});
            for (int i = 0; i < pages.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("rank", i);
                db.update("item_group", v, "id=?", new String[]{String.valueOf(pages.get(i))});
            }
            for (Row f : created.values()) insertFolder(db, f, template);
            for (Row f : dropped) db.delete("item", "id=?", new String[]{String.valueOf(f.id)});
            for (Row f : folders.values()) {
                ContentValues v = new ContentValues();
                v.put("color", f.color);
                v.put("options", f.options);
                db.update("item", v, "id=?", new String[]{String.valueOf(f.id)});
            }
            for (int page : pages) {
                List<Row> l = top.get(page);
                for (int i = 0; i < l.size(); i++) place(db, l.get(i), 0, page, i);
            }
            for (Map.Entry<Integer, List<Row>> e : children.entrySet()) {
                if (!folders.containsKey(e.getKey())) continue;
                List<Row> l = e.getValue();
                for (int i = 0; i < l.size(); i++) place(db, l.get(i), 1, e.getKey(), i);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void place(SQLiteDatabase db, Row r, int containerType, int containerId, int rank) {
        ContentValues v = new ContentValues();
        v.put("container_type", containerType);
        v.put("container_id", containerId);
        v.put("rank", rank);
        db.update("item", v, "id=?", new String[]{String.valueOf(r.id)});
    }

    private void insertPage(SQLiteDatabase db, int id) {
        ContentValues v = copyRow(db, "item_group", pages.isEmpty() ? -1 : pages.get(0));
        if (v.size() == 0) {
            v.put("item_group_position", "{}");
            v.put("grid_x", 0);
            v.put("grid_y", 0);
            v.put("display_type", MAIN_DISPLAY);
            v.put("arrangement", 1);
            v.put("alpha", 1.0);
            v.put("scale", 1.0);
            v.put("angle", 0.0);
            v.put("extend_style", 0);
        }
        v.put("id", id);
        v.put("type", "page");
        v.put("container_id", applistId);
        v.put("rank", pages.indexOf(id));
        fillRequired(db, "item_group", v);
        db.insertOrThrow("item_group", null, v);
    }

    private static void insertFolder(SQLiteDatabase db, Row f, Row template) {
        ContentValues v = template == null ? new ContentValues() : copyRow(db, "item", template.id);
        if (v.size() == 0) {
            v.put("app_widget_id", -1);
            v.put("options", 0);
            v.put("color", 0);
            v.put("profile_id", 0);
            v.put("restored", 0);
            v.put("hidden", 0);
            v.put("spanX", 1);
            v.put("spanY", 1);
            v.put("item_position", "{}");
            v.put("position_x", -1);
            v.put("position_y", -1);
            v.put("alpha", 1.0);
            v.put("scale", 1.0);
            v.put("angle", 0.0);
            v.put("extend_style", 0);
        }
        v.put("id", f.id);
        v.put("type", "folder");
        v.put("title", f.title);
        // A new folder starts with the default color; colorFolders() may set one.
        v.put("color", f.color);
        v.put("options", f.options);
        v.putNull("intent");
        v.putNull("component");
        v.put("container_type", 0);
        v.put("container_id", f.containerId);
        v.put("rank", 0);
        fillRequired(db, "item", v);
        db.insertOrThrow("item", null, v);
    }

    /**
     * Keeps inserts valid across launcher versions: drops values for columns the table no longer
     * has and fills NOT NULL columns without a default that this code doesn't know about (a newer
     * launcher may add some, e.g. homeup_extend_data).
     */
    private static void fillRequired(SQLiteDatabase db, String table, ContentValues v) {
        Set<String> have = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                String col = c.getString(1);
                have.add(col);
                boolean required = c.getInt(3) == 1 && c.isNull(4) && c.getInt(5) == 0;
                if (!required || v.containsKey(col)) continue;
                String type = c.getString(2).toUpperCase(Locale.ROOT);
                if (type.contains("INT")) v.put(col, 0);
                else if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")) v.put(col, 0.0);
                else if (type.contains("BLOB")) v.put(col, new byte[0]);
                else v.put(col, "{}");
            }
        }
        for (String key : new ArrayList<>(v.keySet())) if (!have.contains(key)) v.remove(key);
    }

    /** All columns of one row as ContentValues (empty when the row doesn't exist). */
    private static ContentValues copyRow(SQLiteDatabase db, String table, int id) {
        ContentValues v = new ContentValues();
        try (Cursor c = db.rawQuery("SELECT * FROM " + table + " WHERE id=?", new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst()) return v;
            for (int i = 0; i < c.getColumnCount(); i++) {
                String col = c.getColumnName(i);
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        v.put(col, c.getLong(i));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        v.put(col, c.getDouble(i));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        v.put(col, c.getString(i));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        v.put(col, c.getBlob(i));
                        break;
                    default:
                        v.putNull(col);
                }
            }
        }
        return v;
    }
}
