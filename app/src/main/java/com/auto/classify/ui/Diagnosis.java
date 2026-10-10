package com.auto.classify.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Turns the launcher's self-check report into what the settings screen should show. */
public final class Diagnosis {

    public enum Level { OK, WARN, ERROR, FATAL }

    /** Must match hook.Diag feature ids. */
    public static final String F_CORE = "core";
    public static final String F_LIVE = "live";
    public static final String F_ORGANIZE = "organize";
    public static final String F_BACKUP = "backup";

    public Level level = Level.OK;
    public final List<String> messages = new ArrayList<>();
    /** Features with ERROR-level failures that the user may switch off. */
    public final Set<String> brokenFeatures = new LinkedHashSet<>();
    public String launcherVersion = "?";
    public boolean attached;

    /** Stable key of the current issues, used to remember a dismissal. */
    public String signature() {
        return level + ":" + String.join("|", messages);
    }

    public static Diagnosis noReply(boolean moduleActive) {
        Diagnosis d = new Diagnosis();
        d.level = Level.FATAL;
        d.messages.add(moduleActive
                ? "One UI 홈이 응답하지 않습니다. LSPosed에서 One UI 홈이 스코프에 체크돼 있는지 확인하고 홈을 다시 시작하세요."
                : "LSPosed에서 AutoClassify 모듈이 꺼져 있습니다.");
        return d;
    }

    /**
     * @param disabled features the user already turned off; their failures no longer raise
     *                 an ERROR dialog (the banner still shows).
     */
    public static Diagnosis from(String json, Set<String> disabled) {
        Diagnosis d = new Diagnosis();
        try {
            JSONObject o = new JSONObject(json);
            d.launcherVersion = o.optString("launcherVersion", "?");
            d.attached = o.optBoolean("attached");
            JSONArray checks = o.optJSONArray("checks");
            for (int i = 0; checks != null && i < checks.length(); i++) {
                JSONObject c = checks.getJSONObject(i);
                if (c.optBoolean("ok")) continue;
                String sev = c.optString("severity");
                String feature = c.optString("feature");
                Level lv = "fatal".equals(sev) ? Level.FATAL : "error".equals(sev) ? Level.ERROR : Level.WARN;
                if (lv == Level.ERROR && disabled.contains(feature)) lv = Level.WARN;
                if (lv == Level.ERROR) d.brokenFeatures.add(feature);
                d.messages.add("[" + featureName(feature) + "] " + c.optString("message"));
                if (lv.ordinal() > d.level.ordinal()) d.level = lv;
            }
        } catch (Exception e) {
            d.level = Level.WARN;
            d.messages.add("진단 결과를 읽지 못했습니다: " + e.getMessage());
        }
        return d;
    }

    public static String featureName(String id) {
        switch (id) {
            case F_CORE:
                return "기본";
            case F_LIVE:
                return "설치 즉시 분류";
            case F_ORGANIZE:
                return "정리·미리보기";
            case F_BACKUP:
                return "백업";
            default:
                return id;
        }
    }
}
