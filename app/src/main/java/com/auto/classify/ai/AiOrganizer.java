package com.auto.classify.ai;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import com.auto.classify.ClassifierProvider;
import com.auto.classify.core.Classifier;
import com.auto.classify.core.Config;
import com.auto.classify.core.Developers;
import com.auto.classify.core.Directives;
import com.auto.classify.core.SmartClassifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the AI decide the folders itself, following the user's own instruction ("삼성이랑 구글은
 * 따로, 나머지는 카테고리별로, 너무 작은 폴더는 합치고 큰 건 나눠줘"). Things plain rules can't
 * say (merge small categories, split big ones) are the AI's job here. The answer is an
 * assignment pkg -> folder, which the normal planner then applies; the user's rules still win.
 */
public final class AiOrganizer {

    /** What the AI decided. */
    public static final class Plan {
        /** pkg -> folder name, or Classifier.LOOSE for outside folders. Apps it skipped are absent. */
        public final Map<String, String> assign = new LinkedHashMap<>();
        /** Folder name (or Classifier.LOOSE) -> 1-based page the instruction asked for. */
        public final Map<String, Integer> pages = new LinkedHashMap<>();
        /** Layout directive lines ("!폴더안정렬 색상") the instruction asked for. */
        public final List<String> settings = new ArrayList<>();
        /** App settings the request asked to change ("min_apps=3"), already validated. */
        public final List<String> options = new ArrayList<>();
        /** The AI reply when the user asked a question or wanted an explanation. */
        public String answer = "";
        /** Folder names in the order the apps screen should show them; empty = no opinion. */
        public final List<String> folderOrder = new ArrayList<>();
        /** True for a follow-up (refine): pages and order the answer omits are kept from before. */
        public boolean refined;
        public String note = "";
        public int tokens;
    }

    private static final String RULES_OF_THE_ANSWER =
            "Answer with one JSON object only:\n"
                    + "{\"answer\":\"\", \"folders\":[{\"name\":\"폴더 이름\",\"page\":0,\"apps\":[app numbers]}], \"loose\":[app numbers], "
                    + "\"loose_page\":0, \"folder_order\":[\"폴더 이름\"], \"settings\":[setting lines], \"options\":[\"KEY=VALUE\"], \"note\":\"짧은 한국어 요약\"}\n"
                    + "- page: 0 = anywhere. When the user asks to split things across pages or put folders/apps on a page, "
                    + "set page (1 = first page) on those folders, and loose_page for the loose apps. loose_page moves every app listed "
                    + "in loose, so to put certain apps on a page outside folders, list exactly those apps in loose and set loose_page. "
                    + "Never leave an app out of the answer to place it: an app not listed ends up on page 1.\n"
                    + "- Every app number appears exactly once, in one folder or in loose. The one exception: when the request needs no folder "
                    + "change at all (a question, an explanation, a settings change), return \"folders\":[] and \"loose\":[]; "
                    + "the current organization then stays exactly as it is. Never rearrange folders the request didn't ask about.\n"
                    + "- Follow the user's instruction first. Where it says nothing, group by what the app is for.\n"
                    + "- Folder names: short Korean (or the brand, e.g. 삼성, Google), as the user wrote them when given.\n"
                    + "- \"too few\" / \"too many\" apps in a folder: unless the user gives numbers, merge folders with fewer than MIN apps "
                    + "into the closest related folder, and split folders with more than 20 apps into meaningful sub-groups.\n"
                    + "- The automatic category shown for each app is only a hint and is sometimes wrong; use your knowledge of the app.\n"
                    + "- loose = apps the instruction wants outside folders (keep it empty unless asked).\n"
                    + "- settings: only when the instruction asks about order or folder colors, using exactly these lines:\n"
                    + "  !폴더안정렬 이름|색상|설치일   (order inside folders; 색상 = rainbow order of icon colors)\n"
                    + "  !앱정렬 이름|색상|설치일       (order of apps outside folders)\n"
                    + "  !폴더정렬 이름|색상|앱수       (order of folders)\n"
                    + "  !배치 폴더먼저|앱먼저\n"
                    + "  !폴더색 아이콘|카테고리|기본\n"
                    + "- folder_order: the folder names in the order they should appear on the apps screen, when the user cares about "
                    + "the order of folders (e.g. most used first, alphabetical, a given sequence). Leave it empty to keep the current order. "
                    + "Only folders listed in folders belong here.\n"
                    + "- answer: when the user asks a question or wants an explanation (\"왜 이렇게 했어?\", \"이 앱은 뭐야?\"), "
                    + "answer it here in Korean, short and concrete; folders may then stay as they are. Otherwise leave it empty.\n"
                    + "- note: one or two Korean sentences saying what you did, or why you changed nothing.\n"
                    + "- options: only when the user asks to change an app setting (not the layout). KEY=VALUE pairs from:\n"
                    + AiOptions.prompt().replace("set:", "  ");

    /** There is no separate verification request: the model checks its own plan before it answers (with tools when it has them). */
    private static final String CHECK_YOURSELF =
            "Before you answer, check your own plan against the request: every app is in the place the request (or, where it is silent, "
                    + "the app's real purpose) calls for, folder sizes, order and pages are as asked, and no app is missing. "
                    + "Where a tool can tell you the real state (current layout, folder contents, usage), use it rather than assuming. "
                    + "Fix what is wrong, then answer once.\n";

    private static final String SYSTEM_ORGANIZE =
            "You organize the apps screen (app drawer) of a Samsung Galaxy phone into folders, exactly as the user asks.\n"
                    + CHECK_YOURSELF
                    + RULES_OF_THE_ANSWER;

    private static final String SYSTEM_REFINE =
            "You adjust an existing folder organization of a Samsung Galaxy phone's apps screen.\n"
                    + "Apply the user's follow-up request to the current organization. Change only what the request is about "
                    + "(moving apps, merging or splitting folders, renaming folders, pages, order); keep every other app, "
                    + "folder name and page exactly as it is. If the user asks to start over, organize everything again.\n"
                    + "Return the full organization in the same format, or empty folders and loose when nothing should change.\n"
                    + CHECK_YOURSELF
                    + RULES_OF_THE_ANSWER;

    private static final String SYSTEM_NAMES =
            "You name the folders on a Samsung Galaxy phone's apps screen.\n"
                    + "For each folder you get its current name and its apps. Give it a short, natural name (usually 2-6 Korean "
                    + "characters, or the brand when all apps are from one company) that says what the apps are for. "
                    + "Names must be different from each other. Follow the user's style request if there is one.\n"
                    + "Answer with one JSON object only: {\"names\":{\"current name\":\"new name\"}} including every folder.";

    /**
     * New names for folders: current name -> new name (only the ones that change).
     * @param folders current name -> its app names
     */
    public static Map<String, String> nameFolders(Context context, ApiLlm llm, Map<String, List<String>> folders, String style,
                                                  Progress progress) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (style != null && !style.trim().isEmpty()) sb.append("Style request: ").append(style.trim()).append("\n\n");
        sb.append("Folders:\n");
        for (Map.Entry<String, List<String>> e : folders.entrySet()) {
            List<String> apps = e.getValue();
            sb.append("- ").append(e.getKey()).append(": ")
                    .append(String.join(", ", apps.subList(0, Math.min(15, apps.size()))))
                    .append(apps.size() > 15 ? " … (" + apps.size() + ")" : "").append('\n');
        }
        if (progress != null) progress.on(STAGE_SEND, "");
        try {
            JSONObject o = new JSONObject(json(llm.generate(SYSTEM_NAMES, sb.toString(), true,
                    progress == null ? null : t -> progress.on(AiUsage.KIND_NAMES, t))));
            JSONObject names = o.optJSONObject("names");
            Map<String, String> out = new LinkedHashMap<>();
            java.util.Set<String> used = new java.util.HashSet<>();
            for (String old : folders.keySet()) {
                String n = names == null ? "" : names.optString(old).trim().replace("|", "").replace("#", "").replace("@", "");
                if (n.isEmpty() || !used.add(n)) continue;
                if (!n.equals(old)) out.put(old, n);
            }
            AiUsage.record(context, AiUsage.KIND_NAMES, "폴더 " + out.size() + "개 이름 바꿈", true, llm.lastIn, llm.lastOut);
            return out;
        } catch (Exception e) {
            AiUsage.record(context, AiUsage.KIND_NAMES, "실패: " + e.getMessage(), false, llm.lastIn, llm.lastOut);
            throw e;
        }
    }

    private static final String SYSTEM_ONE =
            "A new app was installed on a Samsung Galaxy phone. Pick its folder on the apps screen, following the user's "
                    + "instruction and the existing folders.\n"
                    + "Answer with one JSON object only: {\"folder\":\"existing folder name\"}. Use a new folder name only when "
                    + "the instruction clearly calls for one; {\"folder\":null} keeps the app outside folders.";

    private AiOrganizer() {
    }

    private static final class Apps {
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
    }

    /** Last brief and the installed-app fingerprint it was made for. */
    private static Apps cached;
    private static String cachedKey;

    /** Timings (ms) of the last brief's steps, for the shell check. */
    public static long[] lastTimes = new long[3];

    /**
     * Every launchable app, numbered from 1, with developer and automatic category. Reused
     * while no app was installed, updated or removed.
     */
    private static synchronized Apps apps(Context context, Progress progress) {
        long t0 = System.currentTimeMillis();
        if (progress != null) progress.on(STAGE_LIST, "");
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        StringBuilder key = new StringBuilder();
        java.util.TreeMap<String, ResolveInfo> byPkg = new java.util.TreeMap<>();
        for (ResolveInfo ri : infos) byPkg.putIfAbsent(ri.activityInfo.packageName, ri);
        for (String pkg : byPkg.keySet()) {
            long updated = 0;
            try {
                updated = pm.getPackageInfo(pkg, 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            key.append(pkg).append(updated).append(';');
        }
        if (cached != null && key.toString().equals(cachedKey)) return cached;

        Apps a = new Apps();
        for (Map.Entry<String, ResolveInfo> e : byPkg.entrySet()) {
            a.pkgs.add(e.getKey());
            a.labels.add(String.valueOf(e.getValue().loadLabel(pm)));
        }
        long t1 = System.currentTimeMillis();
        if (progress != null) progress.on(STAGE_CATEGORY, "");
        String[] cats = ClassifierProvider.classify(context, a.pkgs.toArray(new String[0]), a.labels.toArray(new String[0]),
                false, Config.MODE_CATEGORY).getStringArray("targets");
        long t2 = System.currentTimeMillis();
        if (progress != null) progress.on(STAGE_DEV, "");
        for (int i = 0; i < a.pkgs.size(); i++) {
            String cat = cats == null || cats[i] == null ? "?" : cats[i];
            a.lines.add((i + 1) + ". " + a.labels.get(i) + " | " + a.pkgs.get(i) + " | " + Developers.of(pm, a.pkgs.get(i)) + " | " + cat);
        }
        lastTimes = new long[]{t1 - t0, t2 - t1, System.currentTimeMillis() - t2};
        cached = a;
        cachedKey = key.toString();
        return a;
    }

    /** Progress stages before the AI answers. */
    public static final String STAGE_LIST = "앱 목록 읽는 중";
    public static final String STAGE_CATEGORY = "카테고리 확인 중";
    public static final String STAGE_DEV = "만든 곳 확인 중";
    public static final String STAGE_SEND = "AI에게 보내는 중";

    private static String header(String instruction, int minApps, Apps a) {
        return "User's instruction:\n" + instruction.trim() + "\n\nMIN = " + Math.max(2, minApps)
                + "\nKnown categories: " + String.join(", ", SmartClassifier.broadFolders())
                + "\n\nApps (number. name | package | developer | automatic category, ? = unknown):\n"
                + String.join("\n", a.lines);
    }

    /**
     * A follow-up request on top of the current AI organization ("게임 폴더는 2페이지로",
     * "금융이랑 쇼핑 합쳐"): only what it mentions changes.
     */
    public static Plan refine(Context context, ApiLlm llm, String instruction, Map<String, String> assign,
                              Map<String, Integer> pages, List<String> order, String request, int minApps, Progress progress) throws Exception {
        Apps a = apps(context, progress);
        if (progress != null) progress.on(STAGE_SEND, "");
        Plan current = new Plan();
        current.assign.putAll(assign);
        current.pages.putAll(pages);
        current.folderOrder.addAll(order);
        String user = header(instruction, minApps, a) + "\n\nCurrent organization:\n" + describe(current, a)
                + "\nFollow-up request:\n" + request.trim();
        try {
            Plan p = parse(llm.generate(SYSTEM_REFINE, user, true,
                    progress == null ? null : t -> progress.on(AiUsage.KIND_ORGANIZE, t)), a);
            p.tokens = llm.lastIn + llm.lastOut;
            AiUsage.record(context, AiUsage.KIND_ORGANIZE, "추가 요청: " + request.trim(), true, llm.lastIn, llm.lastOut);
            // An app the answer left out keeps its place.
            for (Map.Entry<String, String> e : assign.entrySet()) p.assign.putIfAbsent(e.getKey(), e.getValue());
            // So do the pages and folder order it didn't mention (as long as the folder still exists).
            java.util.Set<String> live = new java.util.HashSet<>(p.assign.values());
            for (Map.Entry<String, Integer> e : pages.entrySet()) {
                if (live.contains(e.getKey())) p.pages.putIfAbsent(e.getKey(), e.getValue());
            }
            if (p.folderOrder.isEmpty()) {
                for (String n : order) if (live.contains(n)) p.folderOrder.add(n);
            }
            p.refined = true;
            return p;
        } catch (Exception e) {
            AiUsage.record(context, AiUsage.KIND_ORGANIZE, "추가 요청 실패: " + e.getMessage(), false, llm.lastIn, llm.lastOut);
            throw e;
        }
    }

    /** Builds the app brief ahead of time (e.g. when the AI screen opens) so the request starts at once. */
    public static void warmUp(Context context) {
        apps(context, null);
    }

    /** Organizes every app by the instruction in one request; the model checks its own plan before answering. */
    public static Plan organize(Context context, ApiLlm llm, String instruction, int minApps) throws Exception {
        return organize(context, llm, instruction, minApps, null);
    }

    /** Progress: stage ("정리") and the answer text so far. */
    public interface Progress {
        void on(String stage, String text);
    }

    public static Plan organize(Context context, ApiLlm llm, String instruction, int minApps,
                                Progress progress) throws Exception {
        Apps a = apps(context, progress);
        if (progress != null) progress.on(STAGE_SEND, "");
        String header = header(instruction, minApps, a);
        Plan plan;
        try {
            plan = parse(llm.generate(SYSTEM_ORGANIZE, header, true,
                    progress == null ? null : t -> progress.on(AiUsage.KIND_ORGANIZE, t)), a);
            plan.tokens = llm.lastIn + llm.lastOut;
            AiUsage.record(context, AiUsage.KIND_ORGANIZE, "앱 " + a.pkgs.size() + "개 · 폴더 " + folderCount(plan) + "개",
                    true, llm.lastIn, llm.lastOut);
        } catch (Exception e) {
            AiUsage.record(context, AiUsage.KIND_ORGANIZE, "실패: " + e.getMessage(), false, llm.lastIn, llm.lastOut);
            throw e;
        }
        fillMissing(plan, a);
        return plan;
    }

    /**
     * Folder for one new app from the instruction and the current AI folders.
     * @param current pkg -> folder (the stored AI assignment)
     * @return folder name, Classifier.LOOSE, or null when the AI gave no usable answer
     */
    public static String classifyOne(Context context, ApiLlm llm, String instruction, Map<String, String> current,
                                     String pkg, String label) throws Exception {
        PackageManager pm = context.getPackageManager();
        Map<String, List<String>> folders = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : current.entrySet()) {
            if (Classifier.LOOSE.equals(e.getValue())) continue;
            List<String> l = folders.computeIfAbsent(e.getValue(), k -> new ArrayList<>());
            if (l.size() < 8) l.add(labelOf(pm, e.getKey()));
        }
        StringBuilder sb = new StringBuilder("User's instruction:\n").append(instruction.trim()).append("\n\nExisting folders (some apps in each):\n");
        for (Map.Entry<String, List<String>> e : folders.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
        }
        Bundle auto = ClassifierProvider.classify(context, new String[]{pkg}, new String[]{label}, false, Config.MODE_CATEGORY);
        String[] cat = auto.getStringArray("targets");
        sb.append("\nNew app: ").append(label).append(" | ").append(pkg).append(" | ").append(Developers.of(pm, pkg))
                .append(" | automatic category: ").append(cat == null || cat[0] == null ? "?" : cat[0]);
        try {
            String raw = llm.generate(SYSTEM_ONE, sb.toString(), true);
            JSONObject o = new JSONObject(json(raw));
            String folder = o.isNull("folder") ? Classifier.LOOSE : o.optString("folder").trim();
            if (folder.isEmpty()) folder = null;
            AiUsage.record(context, AiUsage.KIND_INSTALL, label + " → " + (Classifier.LOOSE.equals(folder) ? "폴더 밖" : folder),
                    folder != null, llm.lastIn, llm.lastOut);
            return folder;
        } catch (Exception e) {
            AiUsage.record(context, AiUsage.KIND_INSTALL, label + " 실패: " + e.getMessage(), false, llm.lastIn, llm.lastOut);
            throw e;
        }
    }

    private static String labelOf(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int folderCount(Plan p) {
        java.util.Set<String> s = new java.util.HashSet<>(p.assign.values());
        s.remove(Classifier.LOOSE);
        return s.size();
    }

    /** The plan as numbered text for the checking pass. */
    private static String describe(Plan plan, Apps a) {
        Map<String, List<String>> byFolder = new LinkedHashMap<>();
        for (int i = 0; i < a.pkgs.size(); i++) {
            String f = plan.assign.get(a.pkgs.get(i));
            if (f == null) continue;
            byFolder.computeIfAbsent(Classifier.LOOSE.equals(f) ? "(loose)" : f, k -> new ArrayList<>())
                    .add((i + 1) + " " + a.labels.get(i));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byFolder.entrySet()) {
            Integer pg = plan.pages.get(e.getKey().equals("(loose)") ? Classifier.LOOSE : e.getKey());
            sb.append(e.getKey()).append(pg == null ? "" : " [page " + pg + "]").append(" (").append(e.getValue().size()).append("): ")
                    .append(String.join(", ", e.getValue())).append('\n');
        }
        if (!plan.folderOrder.isEmpty()) sb.append("folder order: ").append(String.join(", ", plan.folderOrder)).append('\n');
        if (!plan.settings.isEmpty()) sb.append("settings: ").append(String.join(", ", plan.settings)).append('\n');
        return sb.toString();
    }

    private static Plan parse(String raw, Apps a) throws Exception {
        JSONObject o = new JSONObject(json(raw));
        Plan p = new Plan();
        JSONArray folders = o.optJSONArray("folders");
        for (int i = 0; folders != null && i < folders.length(); i++) {
            JSONObject f = folders.optJSONObject(i);
            if (f == null) continue;
            String name = f.optString("name").trim().replace("|", "").replace("\n", " ");
            if (name.isEmpty()) continue;
            if (f.optInt("page") > 0) p.pages.put(name, f.optInt("page"));
            JSONArray apps = f.optJSONArray("apps");
            for (int j = 0; apps != null && j < apps.length(); j++) put(p, a, apps.opt(j), name);
        }
        if (o.optInt("loose_page") > 0) p.pages.put(Classifier.LOOSE, o.optInt("loose_page"));
        JSONArray loose = o.optJSONArray("loose");
        for (int j = 0; loose != null && j < loose.length(); j++) put(p, a, loose.opt(j), Classifier.LOOSE);
        JSONArray settings = o.optJSONArray("settings");
        for (int i = 0; settings != null && i < settings.length(); i++) {
            String line = settings.optString(i).trim();
            if (line.startsWith("!") && Directives.validateLine(line) == null) p.settings.add(line);
        }
        JSONArray options = o.optJSONArray("options");
        for (int i = 0; options != null && i < options.length(); i++) {
            String n = AiOptions.normalize(options.optString(i));
            if (n != null) p.options.add(n);
        }
        JSONArray order = o.optJSONArray("folder_order");
        for (int i = 0; order != null && i < order.length(); i++) {
            String n = order.optString(i).trim();
            if (!n.isEmpty() && !p.folderOrder.contains(n)) p.folderOrder.add(n);
        }
        p.answer = o.optString("answer").trim();
        p.note = o.optString("note");
        if (p.assign.isEmpty() && p.answer.isEmpty() && p.options.isEmpty() && p.settings.isEmpty()) throw new IllegalStateException("AI 답에서 폴더를 읽지 못했어요");
        return p;
    }

    /** App number (or, from a sloppy answer, the package name) -> folder; first answer wins. */
    /**
     * Apps the AI left out of a full organization go outside folders, so their place is decided by
     * the answer (and its loose_page) instead of by the unclassified-apps setting (which sends them to page 1).
     */
    private static void fillMissing(Plan p, Apps a) {
        if (p.assign.isEmpty()) return;
        int missing = 0;
        for (String pkg : a.pkgs) {
            if (p.assign.putIfAbsent(pkg, Classifier.LOOSE) == null) missing++;
        }
        if (missing > 0) p.note = (p.note.isEmpty() ? "" : p.note + " ") + "(AI가 빠뜨린 앱 " + missing + "개는 폴더 밖에 뒀어요)";
    }

    private static void put(Plan p, Apps a, Object ref, String folder) {
        int idx = -1;
        if (ref instanceof Number) idx = ((Number) ref).intValue() - 1;
        else if (ref != null) {
            String s = ref.toString().trim();
            if (s.matches("\\d+")) idx = Integer.parseInt(s) - 1;
            else idx = a.pkgs.indexOf(s);
        }
        if (idx < 0 || idx >= a.pkgs.size()) return;
        p.assign.putIfAbsent(a.pkgs.get(idx), folder);
    }

    /** The JSON object inside a reply (models sometimes wrap it in text or code fences). */
    static String json(String raw) {
        String t = raw.replaceAll("(?s)<think>.*?</think>", "");
        int s = t.indexOf('{'), e = t.lastIndexOf('}');
        if (s < 0 || e <= s) throw new IllegalStateException("AI 답이 JSON이 아니에요");
        return t.substring(s, e + 1);
    }

    /** Stored assignment (Config.KEY_AI_MAP). */
    public static Map<String, String> load(String json) {
        Map<String, String> m = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return m;
        try {
            JSONObject o = new JSONObject(json);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.getString(k));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    public static String save(Map<String, String> m) {
        return new JSONObject(m).toString();
    }
}
