package com.auto.classify.core;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Category classification that combines several kinds of evidence:
 * <ol>
 *   <li>user rules (always win; "-" excludes),</li>
 *   <li>system roles / services / manifest markers ({@link Signals}),</li>
 *   <li>Google Play data: exact package lookup, else the Naive Bayes model ({@link PlayModel}),</li>
 *   <li>the category the developer declared in the manifest (ApplicationInfo.category).</li>
 * </ol>
 * Evidence for the same folder is merged with a noisy-OR; the best folder is used only when its
 * combined confidence reaches the threshold, otherwise the app is left alone.
 */
public final class SmartClassifier {

    public static final String SIG_ROLES = "roles";
    public static final String SIG_PLAY = "play";
    public static final String SIG_MODEL = "model";
    public static final String SIG_DECLARED = "declared";

    public static final class Result {
        public final String folder;
        public final float confidence;
        public final String reason;
        /** 1-based page for the folder, 0 = no preference. */
        public final int page;
        /** Folder color from a rule (ARGB, FolderColors.DEFAULT = reset), or null. */
        public final Integer color;

        Result(String folder, float confidence, String reason, int page) {
            this(folder, confidence, reason, page, null);
        }

        Result(String folder, float confidence, String reason, int page, Integer color) {
            this.folder = folder;
            this.confidence = confidence;
            this.reason = reason;
            this.page = page;
            this.color = color;
        }
    }

    /** Default folder per Play category (index = PlayModel category id). */
    private static final String[] PLAY_FOLDERS = new String[PlayModel.CATEGORIES.length];

    static {
        for (int i = 0; i < PlayModel.GAME_COUNT; i++) PLAY_FOLDERS[i] = "게임";
        Map<String, String> m = new HashMap<>();
        m.put("Art & Design", "사진·디자인");
        m.put("Photography", "사진·디자인");
        m.put("Auto & Vehicles", "교통·지도");
        m.put("Maps & Navigation", "교통·지도");
        m.put("Travel & Local", "여행");
        m.put("Beauty", "라이프");
        m.put("Lifestyle", "라이프");
        m.put("House & Home", "라이프");
        m.put("Parenting", "라이프");
        m.put("Events", "라이프");
        m.put("Dating", "소셜");
        m.put("Social", "소셜");
        m.put("Communication", "소통");
        m.put("Books & Reference", "책·학습");
        m.put("Comics", "책·학습");
        m.put("Education", "책·학습");
        m.put("Business", "생산성");
        m.put("Productivity", "생산성");
        m.put("Entertainment", "엔터테인먼트");
        m.put("Video Players & Editors", "동영상");
        m.put("Finance", "금융");
        m.put("Shopping", "쇼핑");
        m.put("Food & Drink", "음식");
        m.put("Health & Fitness", "건강");
        m.put("Medical", "건강");
        m.put("Libraries & Demo", "도구");
        m.put("Tools", "도구");
        m.put("Personalization", "꾸미기");
        m.put("Music & Audio", "음악");
        m.put("News & Magazines", "뉴스");
        m.put("Sports", "스포츠");
        m.put("Weather", "날씨");
        for (int i = PlayModel.GAME_COUNT; i < PlayModel.CATEGORIES.length; i++) {
            PLAY_FOLDERS[i] = m.getOrDefault(PlayModel.CATEGORIES[i], "기타");
        }
    }

    private static final float W_PLAY_EXACT = 0.93f;
    private static final float W_MODEL = 0.9f;
    private static final float W_DECLARED = 0.85f;

    private final Classifier rules;
    private final Set<String> signals;
    private final float threshold;
    private final Map<String, String> renames;
    private final PackageManager pm;
    private final PlayModel play;
    private final Signals roles;

    /**
     * @param play     may be null when the bundled data failed to load
     * @param renames  default folder name -> user's name
     */
    public SmartClassifier(Classifier rules, Set<String> signals, float threshold, Map<String, String> renames,
                           PackageManager pm, PlayModel play) {
        this.rules = rules;
        this.signals = signals;
        this.threshold = threshold;
        this.renames = renames;
        this.pm = pm;
        this.play = play;
        this.roles = signals.contains(SIG_ROLES) ? new Signals(pm) : null;
    }

    /** @return the decision, or null to leave the app where it is. */
    public Result classify(String pkg, String label) {
        Result auto = automatic(pkg, label);
        return applyRules(rules, pkg, label, grouped(auto), renames, () -> Developers.of(pm, pkg), auto == null ? null : auto.folder);
    }

    /** True (default): related categories share one folder (메신저 + SNS = 소통). */
    public boolean broad = true;

    private Result grouped(Result auto) {
        return broad && auto != null ? new Result(group(auto.folder), auto.confidence, auto.reason, 0) : auto;
    }

    /** Broad folder of each detailed category; unlisted ones keep their name. */
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();
    private static final Map<String, String> GROUPS_LOWER = new HashMap<>();

    static {
        String[][] g = {
                {"소통", "소통", "소셜"},
                {"미디어", "동영상", "음악", "엔터테인먼트"},
                {"생활", "쇼핑", "음식", "라이프", "여행", "교통·지도", "날씨", "건강", "스포츠"},
                {"생산성", "생산성", "책·학습", "뉴스"},
                {"도구", "도구", "인터넷", "꾸미기", "스토어"},
        };
        for (String[] row : g) {
            for (int i = 1; i < row.length; i++) {
                GROUPS.put(row[i], row[0]);
                GROUPS_LOWER.put(row[i].toLowerCase(java.util.Locale.ROOT), row[0].toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    public static String group(String category) {
        return category == null ? null : GROUPS.getOrDefault(category, category);
    }

    /** group() for lower-cased names, as the rule matcher sees them. */
    static String groupLower(String category) {
        return GROUPS_LOWER.getOrDefault(category, category);
    }

    /** The broad folder names, in display order. */
    public static String[] broadFolders() {
        return new String[]{"게임", "소통", "미디어", "사진·디자인", "생활", "금융", "생산성", "AI", "도구", "루팅·모듈", "기타"};
    }

    /**
     * Rules see the automatic result (cat: tokens) and win over it. A rule hit keeps the user's
     * folder name as written; the automatic folder gets the user's rename.
     */
    public static Result applyRules(Classifier rules, String pkg, String label, Result auto, Map<String, String> renames,
                                    java.util.function.Supplier<String> developer) {
        return applyRules(rules, pkg, label, auto, renames, developer, auto == null ? null : auto.folder);
    }

    /** @param detail the detailed category for cat: tokens (cat:소셜 still finds Instagram inside 소통) */
    public static Result applyRules(Classifier rules, String pkg, String label, Result auto, Map<String, String> renames,
                                    java.util.function.Supplier<String> developer, String detail) {
        Classifier.Hit hit = rules.match(pkg, label, detail, developer);
        if (hit != null) {
            return Classifier.EXCLUDE.equals(hit.folder) ? null : new Result(hit.folder, 1f,
                    Classifier.LOOSE.equals(hit.folder) ? "사용자 규칙: 폴더에서 꺼내기" : "사용자 규칙", hit.page, hit.color);
        }
        if (auto == null) return null;
        return new Result(renames.getOrDefault(auto.folder, auto.folder), auto.confidence, auto.reason, 0);
    }

    /** Automatic evidence only (default folder names, before renames); null below the threshold. */
    private Result automatic(String pkg, String label) {
        Map<String, float[]> evidence = new LinkedHashMap<>(); // folder -> {1 - P(all wrong)}
        Map<String, String> why = new HashMap<>();

        if (roles != null) {
            for (Signals.Hit h : roles.of(pkg, label)) add(evidence, why, h.folder, h.weight, h.reason);
        }
        if (play != null && (signals.contains(SIG_PLAY) || signals.contains(SIG_MODEL))) {
            PlayModel.Guess g = play.guess(pkg, label);
            if (g != null && g.exact && signals.contains(SIG_PLAY)) {
                add(evidence, why, PLAY_FOLDERS[g.category], W_PLAY_EXACT, "Play 스토어: " + PlayModel.CATEGORIES[g.category]);
            } else if (g != null && !g.exact && signals.contains(SIG_MODEL)) {
                add(evidence, why, PLAY_FOLDERS[g.category], W_MODEL * g.confidence,
                        "모델 " + Math.round(g.confidence * 100) + "%: " + PlayModel.CATEGORIES[g.category]);
            }
        }
        if (signals.contains(SIG_DECLARED)) {
            String declared = declaredFolder(pkg);
            if (declared != null) add(evidence, why, declared, W_DECLARED, "앱이 선언한 카테고리");
        }

        String best = null;
        float bestP = 0;
        for (Map.Entry<String, float[]> e : evidence.entrySet()) {
            float p = e.getValue()[0];
            if (p > bestP) {
                bestP = p;
                best = e.getKey();
            }
        }
        if (best == null || bestP < threshold) return null;
        return new Result(best, bestP, why.get(best), 0);
    }

    private static void add(Map<String, float[]> evidence, Map<String, String> why, String folder, float w, String reason) {
        float[] p = evidence.computeIfAbsent(folder, k -> new float[]{0});
        p[0] = 1 - (1 - p[0]) * (1 - w);
        why.merge(folder, reason, (a, b) -> a + " · " + b);
    }

    private String declaredFolder(String pkg) {
        try {
            int cat = pm.getApplicationInfo(pkg, 0).category;
            switch (cat) {
                case ApplicationInfo.CATEGORY_GAME:
                    return "게임";
                case ApplicationInfo.CATEGORY_AUDIO:
                    return "음악";
                case ApplicationInfo.CATEGORY_VIDEO:
                    return "동영상";
                case ApplicationInfo.CATEGORY_IMAGE:
                    return "사진·디자인";
                case ApplicationInfo.CATEGORY_SOCIAL:
                    return "소셜";
                case ApplicationInfo.CATEGORY_NEWS:
                    return "뉴스";
                case ApplicationInfo.CATEGORY_MAPS:
                    return "교통·지도";
                case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                    return "생산성";
                case ApplicationInfo.CATEGORY_ACCESSIBILITY:
                    return "도구";
                default:
                    return null;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** All default folder names, for the rename editor. */
    public static Result colorResult(String folder, String reason) {
        return folder == null ? null : new Result(folder, 1f, reason, 0);
    }

    /** Every category name the classifier can produce (broad and detailed). */
    public static String[] defaultFolders() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(java.util.Arrays.asList(broadFolders()));
        all.addAll(java.util.Arrays.asList(detailedFolders()));
        return all.toArray(new String[0]);
    }

    public static String[] detailedFolders() {
        return new String[]{"게임", "소통", "소셜", "인터넷", "사진·디자인", "동영상", "음악", "엔터테인먼트",
                "AI", "생산성", "도구", "꾸미기", "금융", "쇼핑", "음식", "건강", "교통·지도", "여행", "날씨", "뉴스",
                "책·학습", "라이프", "스포츠", "스토어", "루팅·모듈", "기타"};
    }
}
