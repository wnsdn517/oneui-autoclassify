package com.auto.classify.core;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Category evidence the system itself knows about an app: which roles it can fill (browser,
 * dialer, camera, ...), which special services it ships (keyboard, wallpaper, VPN, ...) and a
 * few manifest markers. These are far more reliable than guessing from the name.
 */
public final class Signals {

    public static final class Hit {
        public final String folder;
        public final float weight;
        public final String reason;

        Hit(String folder, float weight, String reason) {
            this.folder = folder;
            this.weight = weight;
            this.reason = reason;
        }
    }

    private final Map<String, List<Hit>> hits = new HashMap<>();
    private final PackageManager pm;
    /** "path@updateTime" to "is an LSPosed module"; kept across previews, so an APK is opened once until it changes. */
    private static final Map<String, Boolean> XPOSED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Queries the package manager once; reuse the instance for a whole batch. */
    public Signals(PackageManager pm) {
        this.pm = pm;
        // Roles are what the user sees the app as, so they outrank store categories
        // (Play files Chrome under Communication).
        activity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
                "인터넷", 0.99f, "브라우저");
        activity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:0")), "소통", 0.95f, "전화 앱");
        activity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0")), "소통", 0.95f, "문자 앱");
        activity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:a@b.c")), "소통", 0.9f, "메일 앱");
        activity(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), "사진·디자인", 0.9f, "카메라");
        activity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), "교통·지도", 0.85f, "지도 열기");
        selector(Intent.CATEGORY_APP_BROWSER, "인터넷", 0.99f, "브라우저");
        selector(Intent.CATEGORY_APP_EMAIL, "소통", 0.95f, "메일 앱");
        selector(Intent.CATEGORY_APP_MESSAGING, "소통", 0.95f, "메시지 앱");
        selector(Intent.CATEGORY_APP_CONTACTS, "소통", 0.95f, "연락처 앱");
        selector(Intent.CATEGORY_APP_GALLERY, "사진·디자인", 0.9f, "갤러리 앱");
        selector(Intent.CATEGORY_APP_MUSIC, "음악", 0.9f, "음악 앱");
        selector(Intent.CATEGORY_APP_MAPS, "교통·지도", 0.95f, "지도 앱");
        selector(Intent.CATEGORY_APP_CALENDAR, "생산성", 0.9f, "캘린더 앱");
        selector(Intent.CATEGORY_APP_CALCULATOR, "도구", 0.95f, "계산기");
        selector(Intent.CATEGORY_APP_MARKET, "스토어", 0.95f, "앱 스토어");
        selector(Intent.CATEGORY_APP_FILES, "도구", 0.9f, "파일 관리자");
        selector(Intent.CATEGORY_APP_WEATHER, "날씨", 0.95f, "날씨 앱");
        selector(Intent.CATEGORY_APP_FITNESS, "건강", 0.9f, "피트니스 앱");
        activity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "꾸미기", 0.9f, "런처");
        service("android.view.InputMethod", "도구", 0.9f, "키보드");
        service("android.service.wallpaper.WallpaperService", "꾸미기", 0.85f, "배경화면");
        service("android.net.VpnService", "도구", 0.6f, "VPN");
        service("android.accessibilityservice.AccessibilityService", "도구", 0.4f, "접근성 서비스");
    }

    /** Root and hooking tools rarely have store data; their names are distinctive. */
    private static final String[] ROOT_TOOL_WORDS = {"magisk", "kernelsu", "ksu", "apatch", "lsposed", "xposed",
            "shizuku", "vector.manager", "hma", "hidemyapplist", "keyattestation", "integrity", "busybox", "mt.plus",
            "corepatch", "zygisk"};

    /** Root words that only count in the label: "root" inside a package name is too common. */
    private static final String[] ROOT_LABEL_WORDS = {"루팅", "root"};

    /** Name evidence strong enough to beat store data (folder, then words; package or label). */
    private static final String[][] STRONG_KEYWORDS = {
            {"도구", "vpn", "orbot", "torbrowser", "torproject", "프록시", "proxy", "방화벽", "firewall",
                    "tailscale", "wireguard", "zerotier"},
            {"책·학습", "컴시간", "시간표", "timetable", "급식", "학교알리미"},
            // Webtoon/web-novel apps: "카카오페이지" must not read as payment ("페이").
            {"엔터테인먼트", "웹툰", "webtoon", "웹소설", "com.kakao.page"},
            // Location spoofers and root "boosters" need root or hooks to do anything.
            {"루팅·모듈", "fakegps", "fde.ai", "system.optimizer", "mockgps", "gpsspoof"},
    };

    /** AI assistants: Play files them under Productivity, but people look for them together. */
    private static final String[] AI_WORDS = {"chatgpt", "openai", "claude", "anthropic", "gemini", "apps.bard",
            "perplexity", "copilot", "grok", "deepseek", "wrtn", "뤼튼", "character.ai", "에이닷", "clova", "클로바", "mistral"};

    /** Role/service evidence plus manifest markers for one package. */
    public List<Hit> of(String pkg, String label) {
        List<Hit> out = new ArrayList<>(hits.getOrDefault(pkg, new ArrayList<>()));
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            if ((ai.metaData != null && ai.metaData.containsKey("xposedmodule")) || modernXposed(ai)) {
                out.add(new Hit("루팅·모듈", 0.98f, "Xposed 모듈"));
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        String l = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        for (String w : ROOT_TOOL_WORDS) {
            if (p.contains(w) || l.contains(w)) {
                out.add(new Hit("루팅·모듈", 0.97f, "루팅·후킹 도구"));
                break;
            }
        }
        for (String w : ROOT_LABEL_WORDS) {
            if (l.contains(w)) {
                out.add(new Hit("루팅·모듈", 0.97f, "루팅 이름: " + w));
                break;
            }
        }
        // Names that say what the app is. Store data often files these elsewhere (Orbot as Communication),
        // so they outweigh the store's 0.93.
        for (String[] k : STRONG_KEYWORDS) {
            for (int i = 1; i < k.length; i++) {
                if (l.contains(k[i]) || p.contains(k[i])) {
                    out.add(new Hit(k[0], 0.99f, "이름 강조: " + k[i]));
                    break;
                }
            }
        }
        for (String w : AI_WORDS) {
            if (p.contains(w) || l.contains(w)) {
                out.add(new Hit("AI", 0.97f, "AI 서비스"));
                break;
            }
        }
        for (String[] k : KEYWORDS) {
            for (int i = 1; i < k.length; i++) {
                if (l.contains(k[i]) || (k[i].length() >= 4 && p.contains(k[i]))) {
                    out.add(new Hit(k[0], 0.75f, "이름: " + k[i]));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Name keywords (Korean and English) per folder. Store data rarely covers Korean and
     * Samsung system apps; their names usually say what they are. Short words only match the
     * label, never the package name, to avoid accidental hits.
     */
    private static final String[][] KEYWORDS = {
            {"금융", "은행", "뱅크", "bank", "증권", "카드", "페이", "pay", "wallet", "지갑", "토스", "보험", "주식", "invest", "finance"},
            {"쇼핑", "쇼핑", "마켓", "쿠팡", "11번가", "옥션", "지마켓", "무신사", "shop", "mall", "당근", "번개장터"},
            {"음식", "배달", "배민", "요기요", "쿠팡이츠", "food", "recipe", "레시피", "커피", "coffee", "starbucks"},
            {"교통·지도", "지도", "맵", "map", "내비", "navi", "택시", "taxi", "버스", "bus", "지하철", "subway", "tmap", "기차", "korail"},
            {"여행", "여행", "travel", "호텔", "hotel", "항공", "airline", "booking", "숙소"},
            {"날씨", "날씨", "weather"},
            {"게임", "게임", "game"},
            {"소통", "메신저", "messenger", "톡", "talk", "chat", "채팅", "메일", "mail", "문자", "message", "전화", "phone", "contacts", "연락처"},
            {"소셜", "instagram", "facebook", "twitter", "threads", "커뮤니티", "community", "카페", "밴드", "band"},
            {"사진·디자인", "카메라", "camera", "사진", "photo", "갤러리", "gallery", "편집", "editor", "스튜디오", "studio"},
            {"음악", "음악", "뮤직", "music", "멜론", "지니", "radio", "라디오", "podcast", "팟캐스트", "audio", "오디오", "녹음"},
            {"동영상", "영상", "비디오", "video", "tv", "movie", "영화", "player", "플레이어", "넷플릭스", "netflix", "웨이브", "티빙"},
            {"뉴스", "뉴스", "news"},
            {"건강", "건강", "헬스", "health", "fitness", "운동", "병원", "약국", "medical", "수면", "sleep"},
            {"책·학습", "학습", "공부", "영어", "사전", "dictionary", "book", "도서", "웹툰", "toon", "만화", "edu", "study", "강의"},
            {"생산성", "캘린더", "calendar", "일정", "메모", "memo", "노트", "note", "office", "문서", "docs", "pdf", "scan", "스캔", "drive", "드라이브", "회의", "meet", "zoom", "slack", "teams"},
            {"도구", "파일", "file", "manager", "관리", "cleaner", "battery", "배터리", "vpn", "키보드", "keyboard", "터미널", "terminal", "adb", "tool", "도구", "계산기", "calculator", "시계", "clock", "알람", "alarm", "손전등", "flash", "remote", "리모컨", "test", "테스트", "toolbox"},
            {"꾸미기", "테마", "theme", "wallpaper", "배경화면", "아이콘", "icon", "launcher", "런처", "위젯", "widget", "폰트", "font"},
            {"스토어", "스토어", "play스토어", "galaxystore", "appstore"},
    };

    /** LSPosed's newer module API declares itself in META-INF/xposed/ instead of meta-data. */
    private boolean modernXposed(ApplicationInfo ai) {
        if (ai.sourceDir == null) return false;
        String key = ai.sourceDir + "@" + new java.io.File(ai.sourceDir).lastModified();
        Boolean cached = XPOSED.get(key);
        if (cached != null) return cached;
        boolean found = false;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(ai.sourceDir)) {
            found = zip.getEntry("META-INF/xposed/java_init.list") != null
                    || zip.getEntry("META-INF/xposed/module.prop") != null;
        } catch (java.io.IOException ignored) {
        }
        XPOSED.put(key, found);
        return found;
    }

    private void activity(Intent intent, String folder, float weight, String reason) {
        for (ResolveInfo ri : pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
            // Settings' FallbackHome answers HOME during boot; it is not a launcher.
            if (ri.activityInfo.name != null && ri.activityInfo.name.contains("FallbackHome")) continue;
            add(ri.activityInfo.packageName, folder, weight, reason);
        }
    }

    private void selector(String category, String folder, float weight, String reason) {
        activity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category), folder, weight, reason);
    }

    private void service(String action, String folder, float weight, String reason) {
        for (ResolveInfo ri : pm.queryIntentServices(new Intent(action), PackageManager.GET_META_DATA)) {
            add(ri.serviceInfo.packageName, folder, weight, reason);
        }
    }

    private void add(String pkg, String folder, float weight, String reason) {
        List<Hit> l = hits.computeIfAbsent(pkg, k -> new ArrayList<>());
        for (Hit h : l) if (h.folder.equals(folder) && h.reason.equals(reason)) return;
        l.add(new Hit(folder, weight, reason));
    }
}
