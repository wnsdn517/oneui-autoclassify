package com.auto.classify.ai;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import com.auto.classify.ClassifierProvider;
import com.auto.classify.core.Classifier;
import com.auto.classify.core.Config;
import com.auto.classify.core.Developers;
import com.auto.classify.core.Prefs;
import com.auto.classify.core.SmartClassifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a plain-language request ("게임은 2페이지 놀이 폴더로, 업무는 내가 만든 회사 폴더로")
 * into rule lines. The AI only writes rules; they are validated and shown to the user before
 * use, and the deterministic classifier does the actual filing.
 */
public final class RuleAssistant {

    public static final class Result {
        // Built by RuleAssistant, OfflineRules and the AI engines.
        /** Rule and directive lines, plus "# ... 처리할 수 없습니다" notes, ready to use. */
        public final String rules;
        public final int dropped;
        /** Parts of the request the AI said it can't do. */
        public final java.util.List<String> unsupported;
        public final String raw;
        /** The reply when the request was a question rather than something to organize. */
        public String answer = "";
        /** Notes to keep / forget for later requests (lines memo: and forget:). */
        public final java.util.List<String> memoAdd = new ArrayList<>();
        public final java.util.List<String> memoRemove = new ArrayList<>();
        /** App settings the request asked to change ("min_apps=3"), already validated. */
        public final java.util.List<String> options = new ArrayList<>();

        public Result(String rules, int dropped, java.util.List<String> unsupported, String raw) {
            this.rules = rules;
            this.dropped = dropped;
            this.unsupported = unsupported;
            this.raw = raw;
        }
    }

    private static final String SYSTEM =
            "You turn a user's request about organizing the apps screen of a Samsung phone into rule lines.\n"
                    + "Answer with rule lines only, one per line. No explanations, no code fences.\n\n"
                    + "FOLDER RULES  FOLDER[@PAGE]|TOKEN,TOKEN\n"
                    + "  TOKEN: cat:CATEGORY (automatic category), =APP NAME (exact name),\n"
                    + "         pkg:PREFIX or pkg:WILDCARD (pkg:com.google.* , pkg:*.samsung.*),\n"
                    + "         re:REGEX on the package name (re:com\\.(kakao|daum)\\..* ; no commas inside),\n"
                    + "         dev:DEVELOPER (developer shown in the app list, e.g. dev:google, dev:Samsung Corporation),\n"
                    + "         a word contained in the app name, or * (every remaining app).\n"
                    + "  FOLDER is any name; reuse the user's existing folder names exactly when the request refers to them.\n"
                    + "  FOLDER (폴더밖) takes matching apps out of folders, e.g. (폴더밖)|cat:게임\n"
                    + "  FOLDER (제외) leaves matching apps exactly where they are.\n"
                    + "  @PAGE puts the folder (or, with (폴더밖), the loose apps) on that page, 1 = first page.\n"
                    + "  The first matching line wins: specific apps first, categories next, * last.\n\n"
                    + "LAYOUT DIRECTIVES  (one per line, start with !)\n"
                    + "  !폴더안정렬 이름|색상|설치일|그대로   order of apps inside every folder\n"
                    + "  !앱정렬 이름|색상|설치일|그대로       order of apps outside folders\n"
                    + "  !폴더정렬 이름|색상|앱수|그대로       order of folders\n"
                    + "  !배치 폴더먼저|앱먼저|그대로          folders before apps, or apps before folders\n"
                    + "  !최소앱 N                             apps needed before a new folder is created\n"
                    + "  !폴더색 아이콘|카테고리|알록달록|기본|그대로   color folders by their apps' icons, by category, each a different color (알록달록), or reset\n"
                    + "  !아이콘모양 원형|사각|그대로          app icons on the apps screen as circles, squares, or as-is\n"
                    + "FOLDER COLOR for one folder: FOLDER#COLOR, COLOR = 빨강 주황 노랑 초록 청록 파랑 보라 분홍 회색 흰색 검정 기본 or RRGGBB,\n"
                    + "  e.g. 놀이#빨강@2|cat:게임\n\n"
                    + "To keep a lasting preference or fact the user states for later requests, write  memo:<short note>  ; "
                    + "to delete a saved note, write  forget:<a fragment of it>  (rarely needed).\n"
                    + "If the user asks a question or wants an explanation instead, write one line  say:<short Korean answer>  "
                    + "(you may also write rules in the same answer).\n"
                    + AiOptions.prompt() + "\n"
                    + "If part of the request cannot be expressed with the above (for example moving apps to the home\n"
                    + "screen, hiding or uninstalling apps, changing app icons, or widgets), write one line\n"
                    + "for it exactly like:  # \"<that part of the request>\"은(는) 처리할 수 없습니다\n"
                    + "and still write rules for the parts that can be done.\n"
                    + "Use only categories and app names from the lists below.\n\n"
                    + "Example request: 게임은 2페이지 놀이 폴더, 카톡이랑 텔레그램은 메신저, 폴더 안은 이름순, 폴더를 앞에, 위젯도 정리해줘\n"
                    + "Example answer:\n"
                    + "메신저|=카카오톡,=Telegram\n"
                    + "놀이@2|cat:게임\n"
                    + "!폴더안정렬 이름\n"
                    + "!배치 폴더먼저\n"
                    + "# \"위젯도 정리해줘\"은(는) 처리할 수 없습니다\n\n"
                    + "Example request: 구글이 만든 앱은 구글 폴더, com.samsung 으로 시작하는 건 삼성 폴더\n"
                    + "Example answer:\n"
                    + "구글|dev:google\n"
                    + "삼성|pkg:com.samsung.*\n\n"
                    + "Example request: 루팅 폴더에 있는 앱들 다 꺼내고 앱은 색상순으로\n"
                    + "Example answer:\n"
                    + "(폴더밖)|cat:루팅·모듈\n"
                    + "!앱정렬 색상\n";

    private RuleAssistant() {
    }

    /**
     * @param existingFolders folder names currently on the apps screen (the user's own included)
     */
    public static Result run(Context context, Llm llm, String request, List<String> existingFolders) throws Exception {
        return run(context, llm, request, existingFolders, null);
    }

    /** @param onText streamed answer so far (ApiLlm only) */
    public static Result run(Context context, Llm llm, String request, List<String> existingFolders,
                             java.util.function.Consumer<String> onText) throws Exception {
        // A small model does much better fixing a draft than starting from nothing.
        Result draft = runOffline(context, request, existingFolders);
        android.content.SharedPreferences prefs = Prefs.open(context);
        String user = AiMemo.prompt(prefs) + AiOptions.snapshot(prefs) + brief(context, existingFolders) + "\nRequest: " + request.trim()
                + (draft.rules.isEmpty() ? "" : "\nDraft from a simple parser (may be wrong or incomplete; "
                + "fix it and keep what is right):\n" + draft.rules)
                + "\nAnswer:";
        if (llm == null) throw new IllegalStateException("API 키를 먼저 넣어 주세요");
        String raw;
        try {
            raw = onText != null && llm instanceof ApiLlm ? ((ApiLlm) llm).generate(SYSTEM, user, false, onText)
                    : llm.generate(SYSTEM, user);
        } catch (Exception e) {
            if (llm instanceof ApiLlm) AiUsage.record(context, AiUsage.KIND_RULES, "실패: " + e.getMessage(), false,
                    ((ApiLlm) llm).lastIn, ((ApiLlm) llm).lastOut);
            throw e;
        }
        Result r = clean(raw);
        if (llm instanceof ApiLlm) AiUsage.record(context, AiUsage.KIND_RULES, "규칙 " + r.rules.split("\n").length + "줄",
                true, ((ApiLlm) llm).lastIn, ((ApiLlm) llm).lastOut);
        if (r.rules.isEmpty() && !draft.rules.isEmpty()) {
            Result d = new Result(draft.rules, r.dropped, draft.unsupported, raw);
            d.options.addAll(r.options);
            d.answer = r.answer;
            d.memoAdd.addAll(r.memoAdd);
            d.memoRemove.addAll(r.memoRemove);
            return d;
        }
        return r;
    }

    /** The built-in offline engine: no model, no network. */
    public static Result runOffline(Context context, String request, List<String> existingFolders) {
        PackageManager pm = context.getPackageManager();
        List<String> labels = new ArrayList<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) labels.add(String.valueOf(ri.loadLabel(pm)));
        return OfflineRules.build(request, labels, existingFolders);
    }

    /** Keeps only lines that parse as rules. */
    static Result clean(String raw) {
        StringBuilder ok = new StringBuilder();
        java.util.List<String> unsupported = new ArrayList<>();
        int dropped = 0;
        java.util.List<String> options = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        java.util.List<String> memoAdd = new ArrayList<>(), memoRemove = new ArrayList<>();
        // Thinking models write their reasoning first.
        raw = raw.replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)^.*</think>", "");
        for (String line : raw.replace("```", "").split("\n")) {
            String l = line.trim();
            if (l.startsWith("- ") || l.startsWith("* ") && l.length() > 2 && l.charAt(2) != '|') l = l.substring(2).trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("memo:")) {
                memoAdd.add(l.substring(5).trim());
                continue;
            }
            if (l.startsWith("forget:")) {
                memoRemove.add(l.substring(7).trim());
                continue;
            }
            if (l.startsWith("say:")) {
                if (answer.length() > 0) answer.append(' ');
                answer.append(l.substring(4).trim());
                continue;
            }
            if (l.startsWith("set:")) {
                String n = AiOptions.normalize(l);
                if (n != null) options.add(n);
                continue;
            }
            if (l.startsWith("#")) {
                if (l.contains("처리할 수 없")) {
                    unsupported.add(l.substring(1).trim());
                    ok.append(l).append('\n');
                }
                continue;
            }
            if (!l.startsWith("!") && l.indexOf('|') < 0) continue;
            if (Classifier.validate(l) != null) {
                dropped++;
                continue;
            }
            ok.append(l).append('\n');
        }
        Result res = new Result(ok.toString().trim(), dropped, unsupported, raw);
        res.options.addAll(options);
        res.answer = answer.toString();
        res.memoAdd.addAll(memoAdd);
        res.memoRemove.addAll(memoRemove);
        return res;
    }

    /** Categories, the user's folders and every app with its automatic category. */
    private static String brief(Context context, List<String> existingFolders) {
        SharedPreferences p = Prefs.open(context);
        boolean color = Config.MODE_COLOR.equals(p.getString(Config.KEY_MODE, Config.MODE_CATEGORY));

        PackageManager pm = context.getPackageManager();
        Map<String, String> apps = new LinkedHashMap<>(); // pkg -> label
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            apps.putIfAbsent(ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm)));
        }
        String[] pkgs = apps.keySet().toArray(new String[0]);
        String[] labels = apps.values().toArray(new String[0]);
        Bundle auto = ClassifierProvider.classify(context, pkgs, labels, false);
        String[] targets = auto.getStringArray("targets");

        Set<String> categories = new LinkedHashSet<>();
        if (!color) {
            for (String f : SmartClassifier.defaultFolders()) categories.add(f);
        }
        StringBuilder appLines = new StringBuilder();
        for (int i = 0; i < pkgs.length; i++) {
            String cat = targets == null || targets[i] == null ? "?" : targets[i];
            if (color && !"?".equals(cat)) categories.add(cat);
            appLines.append(labels[i]).append(" | ").append(pkgs[i]).append(" | ")
                    .append(Developers.of(pm, pkgs[i])).append(" | ").append(cat).append('\n');
        }
        List<String> folders = new ArrayList<>(existingFolders);
        return "Categories: " + String.join(", ", categories) + "\n"
                + "User's existing folders: " + (folders.isEmpty() ? "(none)" : String.join(", ", folders)) + "\n"
                + "Apps (name | package | developer | automatic category, ? = unknown):\n" + appLines;
    }
}
