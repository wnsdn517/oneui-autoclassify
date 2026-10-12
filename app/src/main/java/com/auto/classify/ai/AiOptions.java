package com.auto.classify.ai;

import android.content.SharedPreferences;

import com.auto.classify.core.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few settings the AI may change when the user asks ("폴더는 3개 이상일 때만 만들어줘"). Each
 * is validated against a fixed list, so a bad answer can't touch anything else (API keys,
 * AI limits, the on/off switch, …). The caller tells the user what changed.
 */
public final class AiOptions {

    private AiOptions() {
    }

    private enum Kind { BOOL, INT, TEXT, CHOICE }

    private static final class Opt {
        final String key, label;
        final Kind kind;
        final int min, max;
        final String[] choices;

        Opt(String key, String label, Kind kind, int min, int max, String... choices) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.choices = choices;
        }
    }

    private static final Map<String, Opt> OPTS = new LinkedHashMap<>();

    static {
        add(new Opt(Config.KEY_CREATE_FOLDERS, "새 폴더 만들기", Kind.BOOL, 0, 0));
        add(new Opt(Config.KEY_MIN_APPS, "폴더를 만드는 최소 앱 수", Kind.INT, 1, 20));
        add(new Opt(Config.KEY_SKIP_MOVED, "직접 옮긴 앱은 건드리지 않기", Kind.BOOL, 0, 0));
        add(new Opt(Config.KEY_CLEAN_STALE, "맞지 않는 자동 폴더 정리", Kind.BOOL, 0, 0));
        add(new Opt(Config.KEY_SCREEN_OFF_TIDY, "화면 꺼질 때 새 앱 위치 정리", Kind.BOOL, 0, 0));
        add(new Opt(Config.KEY_DETAILED, "세부 카테고리", Kind.BOOL, 0, 0));
        add(new Opt(Config.KEY_UNCLASSIFIED, "맞는 폴더가 없을 때", Kind.CHOICE, 0, 0,
                Config.UNCLASSIFIED_KEEP, Config.UNCLASSIFIED_FREE, Config.UNCLASSIFIED_FOLDER, Config.UNCLASSIFIED_PAGE));
        add(new Opt(Config.KEY_UNCLASSIFIED_FOLDER, "맞는 폴더가 없을 때 모을 폴더 이름", Kind.TEXT, 0, 0));
        add(new Opt(Config.KEY_UNCLASSIFIED_PAGE, "맞는 폴더가 없을 때 둘 페이지", Kind.INT, 1, 20));
        // The AI tab's own settings.
        add(new Opt(Config.KEY_AI_INSTALL, "새 앱을 설치하면 AI로 분류", Kind.BOOL, 0, 0));
        add(new Opt(Config.KEY_AI_COOLDOWN, "자동 AI 요청 사이 쿨다운(초)", Kind.INT, 0, 300));
        add(new Opt(Config.KEY_AI_DAILY, "하루 최대 자동 AI 요청", Kind.INT, 1, 100));
    }

    private static void add(Opt o) {
        OPTS.put(o.key, o);
    }

    /** What the AI is told it may change, appended to its instructions. */
    public static String prompt() {
        StringBuilder sb = new StringBuilder("OPTIONS (settings you may change when the request calls for it, as  set:KEY=VALUE ; "
                + "the current values are listed under 'Current settings'; change only what the request is about and never flip a "
                + "setting back and forth):\n");
        for (Opt o : OPTS.values()) {
            sb.append("  set:").append(o.key).append('=');
            switch (o.kind) {
                case BOOL:
                    sb.append("true|false");
                    break;
                case INT:
                    sb.append(o.min).append('-').append(o.max);
                    break;
                case CHOICE:
                    sb.append(String.join("|", o.choices));
                    break;
                default:
                    sb.append("text");
            }
            sb.append("   ").append(o.label);
            if (Config.KEY_UNCLASSIFIED.equals(o.key)) {
                sb.append(" (keep = leave where they are, free = loose on pages no rule uses, folder = gather in one folder "
                        + "(then set the folder name, e.g. 기타), page = loose on one page)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The current value of every setting the AI may change, as lines for the prompt. */
    public static String snapshot(SharedPreferences p) {
        StringBuilder sb = new StringBuilder("Current settings:\n");
        for (Opt o : OPTS.values()) {
            sb.append("  ").append(o.key).append('=');
            switch (o.kind) {
                case BOOL:
                    sb.append(p.getBoolean(o.key, defaultBool(o.key)));
                    break;
                case INT:
                    sb.append(p.getInt(o.key, defaultInt(o.key)));
                    break;
                case CHOICE:
                    sb.append(p.getString(o.key, Config.UNCLASSIFIED_FREE));
                    break;
                default:
                    sb.append(p.getString(o.key, "기타"));
            }
            sb.append("   (").append(o.label).append(")\n");
        }
        return sb.append('\n').toString();
    }

    private static int defaultInt(String key) {
        switch (key) {
            case Config.KEY_MIN_APPS:
                return 2;
            case Config.KEY_AI_COOLDOWN:
            case Config.KEY_AI_DAILY:
                return 30;
            default:
                return 1;
        }
    }

    /** @return the cleaned "key=value" for a valid option line (with or without "set:"), else null */
    public static String normalize(String line) {
        if (line == null) return null;
        String l = line.trim();
        if (l.startsWith("set:")) l = l.substring(4).trim();
        int eq = l.indexOf('=');
        if (eq <= 0) return null;
        Opt o = OPTS.get(l.substring(0, eq).trim());
        if (o == null) return null;
        String v = l.substring(eq + 1).trim();
        switch (o.kind) {
            case BOOL:
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) return null;
                v = v.toLowerCase(java.util.Locale.ROOT);
                break;
            case INT:
                try {
                    int n = Integer.parseInt(v);
                    if (n < o.min || n > o.max) return null;
                    v = String.valueOf(n);
                } catch (NumberFormatException e) {
                    return null;
                }
                break;
            case CHOICE:
                boolean found = false;
                for (String c : o.choices) found |= c.equals(v);
                if (!found) return null;
                break;
            default:
                v = v.replace("|", "").replace("\n", " ");
                if (v.isEmpty() || v.length() > 30) return null;
        }
        return o.key + "=" + v;
    }

    /**
     * Writes the valid lines into [ed] and returns one readable line per real change
     * ("폴더를 만드는 최소 앱 수: 2 → 3"); settings that already have that value are skipped.
     */
    public static List<String> apply(SharedPreferences p, SharedPreferences.Editor ed, List<String> lines) {
        List<String> changes = new java.util.ArrayList<>();
        for (String line : lines) {
            String n = normalize(line);
            if (n == null) continue;
            String key = n.substring(0, n.indexOf('='));
            String v = n.substring(key.length() + 1);
            Opt o = OPTS.get(key);
            String before;
            switch (o.kind) {
                case BOOL:
                    boolean was = p.getBoolean(key, defaultBool(key));
                    before = was ? "켬" : "끔";
                    if (was == Boolean.parseBoolean(v)) continue;
                    ed.putBoolean(key, Boolean.parseBoolean(v));
                    changes.add(o.label + ": " + before + " → " + (Boolean.parseBoolean(v) ? "켬" : "끔"));
                    break;
                case INT:
                    int cur = p.contains(key) ? p.getInt(key, -1) : -1;
                    if (cur == Integer.parseInt(v)) continue;
                    ed.putInt(key, Integer.parseInt(v));
                    changes.add(o.label + ": " + (cur < 0 ? "기본" : String.valueOf(cur)) + " → " + v);
                    break;
                default:
                    String curS = p.getString(key, "");
                    if (v.equals(curS)) continue;
                    ed.putString(key, v);
                    changes.add(o.label + ": " + (curS == null || curS.isEmpty() ? "기본" : curS) + " → " + v);
            }
        }
        return changes;
    }

    private static boolean defaultBool(String key) {
        // Matches the defaults the launcher side reads in ModuleMain.loadPrefs.
        switch (key) {
            case Config.KEY_SKIP_MOVED:
            case Config.KEY_DETAILED:
            case Config.KEY_AI_INSTALL:
                return false;
            default:
                return true;
        }
    }
}
