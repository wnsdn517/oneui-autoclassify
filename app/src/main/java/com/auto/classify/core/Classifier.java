package com.auto.classify.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * User rules. They are checked before any automatic evidence wins.
 *
 * Rule line format:  폴더이름[@페이지]|토큰,토큰,...
 *   토큰        label or package name contains it (case-insensitive)
 *   =토큰       label equals it
 *   pkg:토큰    package name starts with it; with * it is a wildcard (pkg:com.google.*)
 *   re:정규식   package name matches the regular expression (re:com\.(google|android)\..*)
 *   dev:개발자  made by that developer (signing certificate, else package namespace)
 *   cat:카테고리 the automatic classification picked that category (e.g. cat:게임)
 *   *           every app (useful as a last line: "기타|*")
 *   폴더이름 "-" excludes the app; "@2" places the folder on page 2.
 * Lines starting with # are comments. A line that names the app exactly (=name, a picked app
 * re:^pkg$, a full pkg:) wins over broad ones (cat:, dev:, words, *); otherwise the first
 * matching line wins.
 *
 * Folder names are free text, so rules can send apps into folders the user made by hand
 * ("회사|cat:업무") and keep the automatic categories for everything else.
 */
public final class Classifier {

    public static final String EXCLUDE = "-";
    /** Alias of {@link #EXCLUDE} that reads better in Korean rule text. */
    public static final String EXCLUDE_ALIAS = "(제외)";
    /** Take the app out of its folder (it stays on the apps screen). */
    public static final String LOOSE = "(폴더밖)";

    /**
     * A matching rule: target folder, optional page (1-based, 0 = no preference) and optional
     * folder color (ARGB, FolderColors.DEFAULT = reset, null = not set).
     */
    public static final class Hit {
        public final String folder;
        public final int page;
        public final Integer color;

        Hit(String folder, int page, Integer color) {
            this.folder = folder;
            this.page = page;
            this.color = color;
        }
    }

    private static final class Rule {
        final String folder;
        final int page;
        final Integer color;
        final String[] tokens;

        Rule(String folder, int page, Integer color, String[] tokens) {
            this.folder = folder;
            this.page = page;
            this.color = color;
            this.tokens = tokens;
        }
    }

    /** Splits "놀이#빨강@2" / "놀이@2#FF5722" into {name, page, color}. */
    static Object[] parseHead(String head) {
        String name = head.trim();
        int page = 0;
        String color = null;
        for (int pass = 0; pass < 2; pass++) {
            int at = name.lastIndexOf('@');
            int hash = name.lastIndexOf('#');
            if (at > 0 && at > hash && name.substring(at + 1).trim().matches("\\d+")) {
                page = Integer.parseInt(name.substring(at + 1).trim());
                name = name.substring(0, at).trim();
            } else if (hash > 0 && hash > at) {
                color = name.substring(hash + 1).trim();
                name = name.substring(0, hash).trim();
            }
        }
        return new Object[]{name, page, color};
    }

    private final List<Rule> rules = new ArrayList<>();

    public Classifier(String rulesText) {
        if (rulesText == null) return;
        for (String raw : rulesText.split("\n")) {
            String line = raw.trim();
            int bar = line.indexOf('|');
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || bar <= 0) continue;
            Object[] h = parseHead(line.substring(0, bar));
            String head = (String) h[0];
            int page = (Integer) h[1];
            Integer color = FolderColors.parse((String) h[2]);
            String[] tokens = line.substring(bar + 1).split(",");
            for (int i = 0; i < tokens.length; i++) {
                String t = tokens[i].trim();
                // Regexes keep their case (\D vs \d); they are compiled case-insensitive instead.
                tokens[i] = t.toLowerCase(Locale.ROOT).startsWith("re:") ? "re:" + t.substring(3) : t.toLowerCase(Locale.ROOT);
            }
            if (EXCLUDE_ALIAS.equals(head)) head = EXCLUDE;
            rules.add(new Rule(head, page, color, tokens));
        }
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * Checks rule text and returns a message for the first problem, or null when it is valid.
     * Lines are numbered from 1 so the message matches what the user sees.
     */
    public static String validate(String rulesText) {
        if (rulesText == null) return null;
        String[] lines = rulesText.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String where = (i + 1) + "번째 줄: ";
            if (line.startsWith("!")) {
                String err = Directives.validateLine(line);
                if (err != null) return where + err;
                continue;
            }
            int bar = line.indexOf('|');
            if (bar < 0) return where + "'폴더이름|토큰' 형식이 아닙니다 ( | 없음)";
            String rawHead = line.substring(0, bar).trim();
            if (rawHead.isEmpty() || rawHead.startsWith("@") || rawHead.startsWith("#")) return where + "폴더 이름이 비어 있습니다";
            Object[] h = parseHead(rawHead);
            int pg = (Integer) h[1];
            if (rawHead.contains("@") && pg != 0 && (pg < 1 || pg > 20)) return where + "페이지는 1~20 사이여야 합니다";
            if (h[2] != null && FolderColors.parse((String) h[2]) == null) {
                return where + "알 수 없는 색 '" + h[2] + "' (빨강·주황·노랑·초록·청록·파랑·보라·분홍·회색·흰색·검정·기본 또는 #RRGGBB)";
            }
            boolean any = false;
            for (String raw : line.substring(bar + 1).split(",")) {
                String t = raw.trim();
                if (t.isEmpty()) continue;
                if (t.equals("=") || t.equalsIgnoreCase("pkg:") || t.equalsIgnoreCase("cat:")
                        || t.equalsIgnoreCase("re:") || t.equalsIgnoreCase("dev:")) {
                    return where + "'" + t + "' 뒤에 값이 없습니다";
                }
                if (t.toLowerCase(Locale.ROOT).startsWith("re:")) {
                    try {
                        java.util.regex.Pattern.compile(t.substring(3));
                    } catch (java.util.regex.PatternSyntaxException e) {
                        return where + "정규식 오류 '" + t.substring(3) + "': " + e.getDescription();
                    }
                }
                any = true;
            }
            if (!any) return where + "토큰이 없습니다";
        }
        return null;
    }

    /**
     * @param autoFolder what the automatic classification chose (may be null); used by cat: tokens
     * @return the first matching rule, or null
     */
    public Hit match(String pkg, String label, String autoFolder) {
        return match(pkg, label, autoFolder, null);
    }

    /** @param developer looked up only when a dev: token needs it (may be null) */
    public Hit match(String pkg, String label, String autoFolder, java.util.function.Supplier<String> developer) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT).trim();
        String a = autoFolder == null ? null : autoFolder.toLowerCase(Locale.ROOT);
        // An app picked by name beats a category or developer rule above it.
        for (Rule rule : rules) {
            for (String t : rule.tokens) {
                if (specific(t) && matches(t, p, l, a, developer)) return new Hit(rule.folder, rule.page, rule.color);
            }
        }
        for (Rule rule : rules) {
            for (String t : rule.tokens) {
                if (t.isEmpty()) continue;
                if (matches(t, p, l, a, developer)) return new Hit(rule.folder, rule.page, rule.color);
            }
        }
        return null;
    }

    /**
     * AI mode: only rules about specific apps (picked by hand) and exclusions; broad rules would
     * fight the AI's own folders.
     */
    public Hit matchExact(String pkg, String label) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT).trim();
        for (Rule rule : rules) {
            for (String t : rule.tokens) {
                if (specific(t) && matches(t, p, l, null, null)) return new Hit(rule.folder, rule.page, rule.color);
            }
        }
        for (Rule rule : rules) {
            if (!EXCLUDE.equals(rule.folder)) continue;
            for (String t : rule.tokens) {
                if (!t.isEmpty() && !t.startsWith("cat:") && !t.startsWith("dev:") && matches(t, p, l, null, null)) {
                    return new Hit(rule.folder, 0, null);
                }
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern PIN = java.util.regex.Pattern.compile("(?i)re:\\^[a-z0-9_\\\\.]+\\$");

    /** Names one app exactly: =label, a picked app (re:^pkg$) or a full package (pkg:com.x.y). */
    static boolean specific(String t) {
        return t.startsWith("=") || PIN.matcher(t).matches()
                || t.startsWith("pkg:") && !t.contains("*") && !t.endsWith(".") && t.indexOf('.') > 0;
    }

    /** Indexes (0-based, rule lines only) of every rule that matches, top first. */
    public List<Integer> matchingRules(String pkg, String label, String autoFolder,
                                       java.util.function.Supplier<String> developer) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT).trim();
        String a = autoFolder == null ? null : autoFolder.toLowerCase(Locale.ROOT);
        List<Integer> out = new ArrayList<>();
        // Same priority as match(): exact picks first, then the rest top to bottom.
        for (int i = 0; i < rules.size(); i++) {
            for (String t : rules.get(i).tokens) {
                if (specific(t) && matches(t, p, l, a, developer)) {
                    out.add(i);
                    break;
                }
            }
        }
        for (int i = 0; i < rules.size(); i++) {
            if (out.contains(i)) continue;
            for (String t : rules.get(i).tokens) {
                if (!t.isEmpty() && matches(t, p, l, a, developer)) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /** Tokens of rule i (lower-cased, as matched), for spotting rules that can overlap. */
    public List<String> tokensOf(int i) {
        return java.util.Arrays.asList(rules.get(i).tokens);
    }

    public int size() {
        return rules.size();
    }

    private final java.util.Map<String, java.util.regex.Pattern> patterns = new java.util.HashMap<>();

    private boolean matches(String token, String pkg, String label, String auto,
                            java.util.function.Supplier<String> developer) {
        if (token.equals("*")) return true;
        if (token.startsWith("=")) return label.equals(token.substring(1));
        if (token.startsWith("pkg:")) {
            String v = token.substring(4);
            return v.contains("*") ? pattern("glob:" + v).matcher(pkg).matches() : pkg.startsWith(v);
        }
        if (token.startsWith("re:")) return pattern(token).matcher(pkg).matches();
        if (token.startsWith("dev:")) {
            return developer != null && Developers.matches(token.substring(4).trim(), developer.get(), pkg);
        }
        if (token.startsWith("cat:")) {
            // cat:소통 also takes 소셜 apps (same broad folder); cat:소셜 only the SNS ones.
            String c = token.substring(4).trim();
            return auto != null && (auto.equals(c) || SmartClassifier.groupLower(auto).equals(c));
        }
        return label.contains(token) || pkg.contains(token);
    }

    private java.util.regex.Pattern pattern(String token) {
        return patterns.computeIfAbsent(token, t -> {
            if (t.startsWith("glob:")) return globToRegex(t.substring(5));
            try {
                return java.util.regex.Pattern.compile(t.substring(3), java.util.regex.Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException e) {
                return java.util.regex.Pattern.compile("(?!)"); // never matches; validate() reports it
            }
        });
    }

    /** com.google.* -> com\.google\..* */
    static java.util.regex.Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else sb.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return java.util.regex.Pattern.compile(sb.toString(), java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    /** True when any rule uses dev:, so callers know to look developers up. */
    public boolean needsDeveloper() {
        for (Rule r : rules) for (String t : r.tokens) if (t.startsWith("dev:")) return true;
        return false;
    }
}
