package com.auto.classify.ai;

import com.auto.classify.core.Classifier;
import com.auto.classify.core.Directives;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in, offline "AI": turns common Korean requests into rules with plain pattern matching.
 * Always available, no model and no network. Handles one instruction per clause, e.g.
 * "게임은 2페이지 놀이 폴더로, 구글 개발자 앱은 구글 폴더, com.samsung.* 은 삼성 폴더,
 *  루팅 앱은 다 꺼내고, 폴더 안은 이름순, 폴더 먼저".
 */
final class OfflineRules {

    /** Words that point at an automatic category. */
    private static final Map<String, String> CATEGORY_WORDS = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"게임", "게임"}, {"금융", "금융"}, {"은행", "금융"}, {"주식", "금융"}, {"쇼핑", "쇼핑"},
                {"메신저", "소통"}, {"채팅", "소통"}, {"소통", "소통"}, {"메일", "소통"}, {"전화", "소통"},
                {"sns", "소셜"}, {"소셜", "소셜"}, {"인터넷", "인터넷"}, {"브라우저", "인터넷"},
                {"사진", "사진·디자인"}, {"카메라", "사진·디자인"}, {"디자인", "사진·디자인"},
                {"동영상", "동영상"}, {"영상", "동영상"}, {"음악", "음악"}, {"엔터", "엔터테인먼트"},
                {"업무", "생산성"}, {"생산성", "생산성"}, {"회사", "생산성"}, {"인공지능", "AI"}, {"도구", "도구"}, {"유틸", "도구"},
                {"꾸미기", "꾸미기"}, {"테마앱", "꾸미기"}, {"음식", "음식"}, {"배달", "음식"},
                {"건강", "건강"}, {"운동", "건강"}, {"지도", "교통·지도"}, {"교통", "교통·지도"},
                {"여행", "여행"}, {"날씨", "날씨"}, {"뉴스", "뉴스"}, {"학습", "책·학습"}, {"공부", "책·학습"},
                {"책", "책·학습"}, {"라이프", "라이프"}, {"스포츠", "스포츠"}, {"스토어", "스토어"},
                {"루팅", "루팅·모듈"}, {"루트", "루팅·모듈"}, {"root", "루팅·모듈"}, {"모듈", "루팅·모듈"},
                {"xposed", "루팅·모듈"},
        };
        for (String[] p : pairs) CATEGORY_WORDS.put(p[0], p[1]);
    }

    /** Korean company names -> what dev: should look for. */
    private static final Map<String, String> DEVELOPERS = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"구글", "google"}, {"삼성", "samsung"}, {"카카오", "kakao"}, {"네이버", "naver"},
                {"마이크로소프트", "microsoft"}, {"메타", "facebook"}, {"페이스북", "facebook"},
                {"라인", "linecorp"}, {"쿠팡", "coupang"}, {"토스", "toss"}, {"오픈ai", "openai"},
        };
        for (String[] p : pairs) DEVELOPERS.put(p[0], p[1]);
    }

    /** Korean names people use for apps whose label is English (and short forms). */
    private static final String[][] APP_ALIASES = {
            {"유튜브", "youtube"}, {"유튭", "youtube"}, {"크롬", "chrome"}, {"카톡", "카카오톡"}, {"텔레그램", "telegram"},
            {"텔레", "telegram"}, {"인스타그램", "instagram"}, {"인스타", "instagram"}, {"페북", "facebook"},
            {"페이스북", "facebook"}, {"스포티파이", "spotify"}, {"넷플릭스", "netflix"}, {"넷플", "netflix"},
            {"디스코드", "discord"}, {"왓츠앱", "whatsapp"}, {"트위터", "twitter"}, {"틱톡", "tiktok"},
            {"지메일", "gmail"}, {"구글맵", "지도"}, {"플레이스토어", "google play 스토어"},
            {"플레이 스토어", "google play 스토어"}, {"파이어폭스", "firefox"}, {"챗지피티", "chatgpt"}, {"챗gpt", "chatgpt"},
            {"제미나이", "gemini"}, {"클로드", "claude"}, {"터뮤", "termux"}, {"터먹스", "termux"},
            {"엣지", "edge"}, {"웨일", "whale"}, {"슬랙", "slack"}, {"노션", "notion"},
    };

    private static final String[] UNSUPPORTED = {"위젯", "홈화면", "홈 화면", "숨기", "숨겨", "삭제", "지워", "제거",
            "앱 아이콘", "아이콘 바꿔", "배경", "테마 바꿔", "언인스톨"};

    private static final Pattern PAGE = Pattern.compile("(\\d+)\\s*(페이지|쪽|번째 ?페이지|번 ?페이지)");
    private static final String[][] ORDINAL_PAGES = {{"첫", "1"}, {"둘째", "2"}, {"두번째", "2"}, {"두 번째", "2"},
            {"셋째", "3"}, {"세번째", "3"}, {"세 번째", "3"}, {"넷째", "4"}, {"네번째", "4"}, {"네 번째", "4"},
            {"다섯번째", "5"}, {"다섯 번째", "5"}};
    private static final Pattern MIN = Pattern.compile(
            "(\\d+)\\s*개\\s*(?:의\\s*)?(?:앱\\s*)?(?:이상|부터|넘|되|모이|일 ?때|있)");
    private static final Pattern MIN_PLAIN = Pattern.compile("최소\\s*(\\d+)");
    /** "네이버로 시작하는 패키지", "com.kakao 로 시작하는 앱". */
    private static final Pattern STARTS = Pattern.compile("([\\p{L}\\p{N}._]+?)\\s*(?:으로|로)\\s*시작하는");
    /** "도구 폴더 이름을 유틸로 바꿔줘", "도구 폴더를 유틸로 이름 변경". */
    private static final Pattern RENAME = Pattern.compile(
            "([\\p{L}\\p{N}·_-]+)\\s*폴더\\s*(?:의\\s*)?(?:이름\\s*)?(?:을|를|은|는)?\\s*['\"]?([\\p{L}\\p{N}·_-]+?)['\"]?\\s*(?:으로|로)\\s*(?:이름\\s*)?(?:바꿔|바꾸|변경|고쳐|해)");
    /** "… 은 네이버", "… 는 네이버로 넣어": a folder named without the word 폴더. */
    private static final Pattern TAIL_NAME = Pattern.compile(
            "(?:은|는|을|를)\\s+['\"]?([\\p{L}\\p{N}·_-]+)['\"]?(?:\\s+\\p{L}*)*\\s*$");
    private static final Set<String> NOT_TAIL = new LinkedHashSet<>(java.util.Arrays.asList(
            "다", "전부", "모두", "같이", "함께", "하나", "한곳", "한", "따로", "각각", "알아서", "폴더", "앱", "정리", "분류",
            "넣어", "넣어줘", "옮겨", "옮겨줘", "묶어", "묶어줘", "모아", "모아줘", "해줘", "줘", "건드리지", "그대로"));
    /** Things a rule cannot know about. */
    private static final String[][] IMPOSSIBLE = {
            {"자주 쓰", "앱 사용 기록은 볼 수 없어요"}, {"많이 쓰", "앱 사용 기록은 볼 수 없어요"},
            {"최근에 쓴", "앱 사용 기록은 볼 수 없어요"}, {"안 쓰는", "앱 사용 기록은 볼 수 없어요"},
            {"용량", "앱 용량으로는 나눌 수 없어요"}, {"알림", "알림 정보로는 나눌 수 없어요"},
    };
    private static final Pattern PKG = Pattern.compile("([a-z][a-z0-9_]*(?:\\.[a-z0-9_*]+)+\\.?\\*?)");
    private static final Pattern QUOTED = Pattern.compile("['\"‘’“”「」]([^'\"‘’“”「」]+)['\"‘’“”「」]");
    private static final Pattern FOLDER_NAME = Pattern.compile("([\\p{L}\\p{N}·_-]+)\\s*폴더");
    private static final Pattern DEV_BEFORE = Pattern.compile(
            "([\\p{L}\\p{N}.]+?)(?:이|가|에서|사에서|사가|사)?\\s*(개발자|만든|개발한|제작한|회사 앱)");
    /** "삼성 기본앱", "구글 순정 앱": preinstalled apps of that maker. */
    private static final Pattern STOCK_APPS = Pattern.compile("([\\p{L}\\p{N}]+?)\\s*(?:기본\\s*앱|순정\\s*앱|기본앱)");
    private static final Pattern DEV_AFTER = Pattern.compile("개발자\\s*(?:가|는|이)?\\s*([\\p{L}\\p{N}.]+)");

    private OfflineRules() {
    }

    /**
     * @param apps    installed app labels
     * @param folders the user's existing folder names
     */
    static RuleAssistant.Result build(String request, List<String> apps, List<String> folders) {
        List<String> lines = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        Set<String> directives = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        List<String> sortedApps = new ArrayList<>(apps);
        sortedApps.sort((a, b) -> b.length() - a.length()); // longest names first
        // "토스, 쿠팡, 배달의민족을 생활 폴더에": bare names before a comma wait for the clause
        // that says where they go.
        List<String> carried = new ArrayList<>();
        List<String> carriedText = new ArrayList<>();

        // "음악, 동영상을 미디어로 합쳐": a merge sentence keeps its commas, so it is handled
        // whole before the request is cut into clauses.
        StringBuilder rest = new StringBuilder();
        for (String sentence : request.split("\\n|;|\\.(?=\\s|$)|그리고")) {
            String c = sentence.trim();
            if (c.isEmpty()) continue;
            if (!merge(c, c.toLowerCase(Locale.ROOT), new ArrayList<>(), lines)) rest.append(c).append('\n');
        }
        request = rest.toString();

        // A period only ends a clause before a space or the end; package names keep theirs.
        // "…넣고 …뽑아줘" is two instructions: split after verb stems followed by 고.
        for (String raw : request.split("[,\\n;]|\\.(?=\\s|$)|그리고|고 나서"
                + "|(?<=넣|하|빼|꺼내|만들|모으|묶|옮기|두|놓|뽑|정리하|분류하)고\\s")) {
            String c = raw.trim();
            if (c.isEmpty()) continue;
            String lc = c.toLowerCase(Locale.ROOT);

            if (folderColorDirective(lc, directives)) continue;
            String impossible = null;
            for (String[] im : IMPOSSIBLE) if (lc.contains(im[0])) impossible = im[1];
            if (impossible != null) {
                unsupported.add("\"" + c + "\"은(는) 처리할 수 없습니다 (" + impossible + ")");
                continue;
            }
            if (lc.matches(".*(카테고리|분류)\\s*(별|대로|기준)?\\s*(로|으로)?\\s*(정리|분류|나눠|나누).*")) {
                notes.add("카테고리별 자동 분류는 기본 동작이에요. 정리 탭에서 '카테고리'를 고르면 돼요");
                continue;
            }
            if (lc.matches(".*(색|색상|컬러)\\s*(별|대로|기준)?\\s*(로|으로)?\\s*(정리|분류|나눠|나누|묶).*")) {
                notes.add("색상별 분류는 정리 탭에서 '색상'을 고르면 돼요");
                continue;
            }
            if (rename(c, lines)) continue;
            if (merge(c, lc, carried, lines)) {
                carried.clear();
                carriedText.clear();
                continue;
            }

            boolean bad = false;
            for (String u : UNSUPPORTED) if (lc.contains(u)) bad = true;
            if (bad) {
                unsupported.add("\"" + c + "\"은(는) 처리할 수 없습니다");
                continue;
            }

            if (directive(lc, directives)) continue;

            List<String> tokens = subjects(c, lc, sortedApps);
            if (!carried.isEmpty()) {
                for (int k = carried.size() - 1; k >= 0; k--) if (!tokens.contains(carried.get(k))) tokens.add(0, carried.get(k));
                carried.clear();
                carriedText.clear();
            }
            if (tokens.isEmpty()) {
                unsupported.add("\"" + c + "\"은(는) 처리할 수 없습니다 (대상 앱을 찾지 못함)");
                continue;
            }

            int page = page(lc);

            // Several developers and no folder named: one folder per developer.
            List<String> devs = new ArrayList<>();
            for (String t : tokens) if (t.startsWith("dev:")) devs.add(t);
            boolean takeOut = lc.contains("꺼내") || lc.contains("빼") || lc.contains("뽑") || lc.contains("폴더 밖으로")
                    || lc.contains("폴더밖으로");
            if (devs.size() >= 2 && !takeOut && explicitFolder(c) == null) {
                for (String d : devs) {
                    lines.add(devFolder(d.substring(4)) + (page > 0 ? "@" + page : "") + "|" + d);
                }
                continue;
            }

            String head;
            if (takeOut) {
                head = Classifier.LOOSE + (page > 0 ? "@" + page : "");
            } else if (lc.contains("건드리지") || lc.contains("그대로 둬") || lc.contains("그대로 두") || lc.contains("제외")) {
                head = Classifier.EXCLUDE_ALIAS;
            } else {
                String folder = folderName(c, folders, tokens);
                if (folder == null && isBareList(lc)) {
                    // Only names: wait for the next clause to say where they go.
                    carried.addAll(tokens);
                    carriedText.add(c);
                    continue;
                }
                if (folder == null) {
                    unsupported.add("\"" + c + "\"은(는) 처리할 수 없습니다 (폴더 이름을 찾지 못함)");
                    continue;
                }
                String color = colorWord(lc);
                head = folder + (color != null ? "#" + color : "") + (page > 0 ? "@" + page : "");
            }
            lines.add(head + "|" + String.join(",", tokens));
        }

        for (String c : carriedText) unsupported.add("\"" + c + "\"은(는) 처리할 수 없습니다 (폴더 이름을 찾지 못함)");
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        for (String n : notes) sb.append("# 참고: ").append(n).append('\n');
        for (String d : directives) sb.append(d).append('\n');
        for (String u : unsupported) sb.append("# ").append(u).append('\n');
        return new RuleAssistant.Result(sb.toString().trim(), 0, unsupported, request);
    }

    /** 1-based page from "3페이지", "첫 페이지", "두 번째 페이지"; 0 = none. */
    private static int page(String lc) {
        Matcher pm = PAGE.matcher(lc);
        if (pm.find()) return Integer.parseInt(pm.group(1));
        for (String[] o : ORDINAL_PAGES) {
            if (lc.matches(".*" + Pattern.quote(o[0]) + "\\s*(번째\\s*)?(페이지|장|쪽|화면).*")) return Integer.parseInt(o[1]);
        }
        return 0;
    }

    /** A clause that only lists apps/categories: no verb, no folder, no page. */
    private static boolean isBareList(String lc) {
        return !lc.contains("폴더") && page(lc) == 0
                && !lc.matches(".*(넣|옮기|옮겨|묶|모아|모으|보내|정리|분류|꺼내|빼|뽑|해줘|하고|로$|으로$|에$).*");
    }

    /** "도구 폴더 이름을 유틸로 바꿔줘": a category folder gets a new name through a rule. */
    private static boolean rename(String c, List<String> lines) {
        Matcher m = RENAME.matcher(c);
        if (!m.find()) return false;
        String from = m.group(1).trim(), to = m.group(2).trim();
        if (to.isEmpty() || to.equals(from)) return false;
        String cat = CATEGORY_WORDS.containsValue(from) ? from : CATEGORY_WORDS.get(from.toLowerCase(Locale.ROOT));
        if (cat == null) return false;
        lines.add(to + "|cat:" + cat);
        return true;
    }

    private static final Pattern MERGE_INTO = Pattern.compile("([\\p{L}\\p{N}·]+?)\\s*(?:폴더)?\\s*(?:으로|로)\\s*(?:합|병합|통합|묶)");

    /**
     * "게임이랑 엔터 폴더 합쳐줘", "음악, 동영상을 미디어로 합쳐": one folder for several
     * categories, named as asked (else after the first one).
     */
    private static boolean merge(String c, String lc, List<String> carried, List<String> lines) {
        if (!lc.matches(".*(합쳐|합치|합해|병합|통합|하나로).*")) return false;
        List<String> cats = new ArrayList<>();
        for (String t : carried) if (t.startsWith("cat:") && !cats.contains(t.substring(4))) cats.add(t.substring(4));
        // In order of appearance; longer words first so "사진·디자인" beats "사진".
        java.util.TreeMap<Integer, String> found = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : CATEGORY_WORDS.entrySet()) {
            for (String w : new String[]{e.getKey(), e.getValue().toLowerCase(Locale.ROOT)}) {
                int at = lc.indexOf(w);
                if (at >= 0 && !found.containsValue(e.getValue())) found.put(at, e.getValue());
            }
        }
        for (String v : found.values()) if (!cats.contains(v)) cats.add(v);
        if (cats.size() < 2) return false;
        Matcher into = MERGE_INTO.matcher(c);
        String name = into.find() ? into.group(1).trim() : cats.get(0);
        if (name.equals("하나") || name.equals("한") || name.equals("한개") || name.equals("폴더")) name = cats.get(0);
        // "게임이랑 엔터로 합쳐" names a category itself: keep that category's folder name.
        String asCat = CATEGORY_WORDS.get(name.toLowerCase(Locale.ROOT));
        if (asCat != null) name = asCat;
        List<String> tokens = new ArrayList<>();
        for (String cat : cats) tokens.add("cat:" + cat);
        lines.add(name + "|" + String.join(",", tokens));
        return true;
    }

    /** "폴더 색은 아이콘 색으로 / 카테고리별로 / 알록달록하게 / 기본으로". */
    private static boolean folderColorDirective(String lc, Set<String> out) {
        if (!(lc.contains("폴더 색") || lc.contains("폴더색") || lc.contains("폴더 컬러") || lc.contains("폴더들 색")
                || lc.contains("폴더의 색"))) return false;
        String v = lc.contains("알록달록") || lc.contains("다채") || lc.contains("화려") || lc.contains("무지개")
                || lc.contains("다르게") || lc.contains("다양") ? Directives.RAINBOW
                : lc.contains("아이콘") || lc.contains("앱 색") ? Directives.ICON
                : lc.contains("카테고리") || lc.contains("분류별") ? Directives.CATEGORY
                : lc.contains("기본") || lc.contains("초기화") || lc.contains("지워") || lc.contains("없애") ? Directives.RESET
                : null;
        if (v == null) return false;
        out.add("!" + Directives.D_FOLDER_COLOR + " " + v);
        return true;
    }

    /** "빨간색으로", "파란 폴더" ... -> rule color name, or null. */
    private static String colorWord(String lc) {
        String[][] words = {{"빨간", "빨강"}, {"주황", "주황"}, {"노란", "노랑"}, {"초록", "초록"}, {"녹색", "초록"},
                {"청록", "청록"}, {"파란", "파랑"}, {"보라", "보라"}, {"분홍", "분홍"}, {"핑크", "분홍"}, {"회색", "회색"},
                {"하얀", "흰색"}, {"흰색", "흰색"}, {"검은", "검정"}, {"검정", "검정"}};
        for (String[] w : words) {
            boolean adjective = w[0].endsWith("간") || w[0].endsWith("란") || w[0].endsWith("얀") || w[0].endsWith("은");
            if (lc.contains(w[0] + "색") || lc.contains(w[0] + " 폴더") || lc.contains(w[0] + "으로")
                    || adjective && lc.matches(".*" + w[0] + "\\s.*폴더.*")) return w[1];
        }
        Matcher m = Pattern.compile("#([0-9a-f]{6})").matcher(lc);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** Sorting/placement/min-apps sentences. @return true when the clause was one. */
    private static boolean directive(String lc, Set<String> out) {
        boolean found = false;
        String by = lc.contains("이름") || lc.contains("가나다") || lc.contains("abc") ? Directives.NAME
                : lc.contains("색") ? Directives.COLOR
                : lc.contains("설치") || lc.contains("최신") || lc.contains("새로 깐") ? Directives.INSTALLED : null;
        boolean sorting = lc.contains("정렬") || lc.contains("순") || lc.contains("순서");
        if (sorting && by != null) {
            if (lc.contains("폴더 안") || lc.contains("폴더안") || lc.contains("폴더 속") || lc.contains("폴더 내")) {
                out.add("!" + Directives.D_INSIDE + " " + by);
                found = true;
            } else if (lc.contains("폴더끼리") || lc.contains("폴더 순서") || lc.contains("폴더들")
                    || lc.matches(".*폴더(를|는|은|도)?\\s*(이름|가나다|abc|설치|최신).*") && !lc.contains("앱")) {
                out.add("!" + Directives.D_FOLDERS + " " + (Directives.COLOR.equals(by) ? Directives.NAME : by));
                found = true;
            } else if (lc.contains("폴더 밖") || lc.contains("밖에") || lc.contains("앱") || lc.contains("아이콘들")) {
                out.add("!" + Directives.D_LOOSE + " " + by);
                found = true;
            }
        }
        if (sorting && (lc.contains("많은") || lc.contains("개수") || lc.contains("크기")) && lc.contains("폴더")) {
            out.add("!" + Directives.D_FOLDERS + " " + Directives.SIZE);
            found = true;
        }
        if (lc.matches(".*폴더(를|가|는)?\\s*(먼저|앞|위).*")) {
            out.add("!" + Directives.D_PLACEMENT + " " + Directives.FOLDERS_FIRST);
            found = true;
        } else if (lc.matches(".*앱(을|이|은)?\\s*(먼저|앞|위).*")) {
            out.add("!" + Directives.D_PLACEMENT + " " + Directives.APPS_FIRST);
            found = true;
        }
        Matcher m = MIN.matcher(lc);
        Matcher mp = MIN_PLAIN.matcher(lc);
        String n = m.find() ? m.group(1) : mp.find() ? mp.group(1) : null;
        if (lc.contains("폴더") && n != null && !lc.contains("페이지")) {
            out.add("!" + Directives.D_MIN_APPS + " " + n);
            found = true;
        }
        return found;
    }

    /** Which apps the clause is about, as rule tokens. */
    private static List<String> subjects(String c, String lc, List<String> apps) {
        List<String> tokens = new ArrayList<>();
        String rest0 = lc;
        Matcher sm = STARTS.matcher(c);
        while (sm.find()) {
            String w = sm.group(1).toLowerCase(Locale.ROOT);
            String devKey = DEVELOPERS.get(w);
            if (w.matches("[a-z][a-z0-9_.]*")) tokens.add("pkg:" + w);
            else if (devKey != null) tokens.add("dev:" + devKey);
            rest0 = rest0.replace(sm.group(0).toLowerCase(Locale.ROOT), " ");
        }
        Matcher pm = PKG.matcher(rest0);
        while (pm.find()) {
            String p = pm.group(1);
            if (p.endsWith(".")) p = p + "*";
            tokens.add("pkg:" + p);
        }
        // Every developer mentioned: "구글이 만든", "삼성 개발자", "삼성 기본앱 / 구글 기본앱".
        Set<String> devWords = new LinkedHashSet<>();
        for (Pattern pat : new Pattern[]{DEV_BEFORE, STOCK_APPS}) {
            Matcher m = pat.matcher(c);
            while (m.find()) devWords.add(m.group(1));
        }
        Matcher da = DEV_AFTER.matcher(c);
        if (devWords.isEmpty() && da.find()) devWords.add(da.group(1));
        // "구글 앱", "카카오 앱들", "삼성 거": a known company followed by app words.
        for (String d : DEVELOPERS.keySet()) {
            if (lc.matches(".*" + Pattern.quote(d) + "\\s*(앱|어플|계열|꺼|거|제품|서비스).*")) devWords.add(d);
        }
        String dev = devWords.isEmpty() ? null : devWords.iterator().next();
        for (String d : devWords) {
            String key = DEVELOPERS.getOrDefault(d.toLowerCase(Locale.ROOT), d.toLowerCase(Locale.ROOT));
            if (!tokens.contains("dev:" + key)) tokens.add("dev:" + key);
        }
        String rest = rest0;
        for (String d : devWords) {
            rest = rest.replaceAll(Pattern.quote(d.toLowerCase(Locale.ROOT)) + "\\s*(앱들|앱|어플|계열|꺼|거|제품|서비스)", " ");
        }
        // The target folder's name is not a subject ("쇼핑은 쇼핑 폴더").
        String target = explicitFolder(c);
        if (target != null) rest = rest.replace(target.toLowerCase(Locale.ROOT) + " 폴더", " 폴더")
                .replace(target.toLowerCase(Locale.ROOT) + "폴더", " 폴더");
        // "… 은 네이버": that last word is the folder, not an app called 네이버.
        String tailFolder = target == null ? tailName(c) : null;
        if (tailFolder != null && !NOT_TAIL.contains(tailFolder)) {
            int at = rest.lastIndexOf(tailFolder.toLowerCase(Locale.ROOT));
            if (at > 0) rest = rest.substring(0, at) + " " + rest.substring(at + tailFolder.length());
        }
        for (String[] al : APP_ALIASES) {
            if (!rest.contains(al[0])) continue;
            for (String app : apps) {
                if (app.toLowerCase(Locale.ROOT).equals(al[1]) && !tokens.contains("=" + app)) {
                    tokens.add("=" + app);
                    rest = rest.replace(al[0], " ");
                    break;
                }
            }
        }
        for (String app : apps) {
            String a = app.toLowerCase(Locale.ROOT);
            if (a.length() >= 2 && rest.contains(a)) {
                tokens.add("=" + app);
                rest = rest.replace(a, " ");
            }
        }
        for (Map.Entry<String, String> e : CATEGORY_WORDS.entrySet()) {
            if (rest.contains(e.getKey()) && !tokens.contains("cat:" + e.getValue())
                    && !(dev != null && e.getKey().equals(dev))) {
                // "놀이 폴더" style target names are not categories.
                if (rest.matches(".*" + Pattern.quote(e.getKey()) + "\\s*폴더.*")) continue;
                tokens.add("cat:" + e.getValue());
            }
        }
        // "게임 폴더 3페이지로": the folder's own category/company is the subject.
        if (tokens.isEmpty() && target != null) {
            String t = target.toLowerCase(Locale.ROOT);
            if (CATEGORY_WORDS.containsKey(t)) tokens.add("cat:" + CATEGORY_WORDS.get(t));
            else if (CATEGORY_WORDS.containsValue(target)) tokens.add("cat:" + target);
            else if (DEVELOPERS.containsKey(t)) tokens.add("dev:" + DEVELOPERS.get(t));
        }
        if (lc.contains("나머지") || lc.contains("모든 앱")
                || tokens.isEmpty() && lc.matches("(.*\\s)?(다|전부|모두|모든|싹|몽땅)(\\s.*)?")) tokens.add("*");
        return tokens;
    }

    /** Quoted name, "X 폴더", or a category/developer name when the clause only names one. */
    private static String folderName(String c, List<String> folders, List<String> tokens) {
        String explicit = explicitFolder(c);
        if (explicit != null) {
            for (String existing : folders) if (existing.equalsIgnoreCase(explicit)) return existing;
            return explicit;
        }
        String n = tailName(c);
        if (n != null) {
            boolean subject = false;
            for (String t : tokens) if (t.equalsIgnoreCase("=" + n)) subject = true;
            if (!NOT_TAIL.contains(n) && !subject && !n.matches("\\d.*") && !n.endsWith("페이지")) {
                for (String existing : folders) if (existing.equalsIgnoreCase(n)) return existing;
                return n;
            }
        }
        for (String t : tokens) if (t.startsWith("cat:")) return t.substring(4);
        for (String t : tokens) if (t.startsWith("dev:")) return devFolder(t.substring(4));
        return null;
    }

    /** "… 은 네이버로 넣어줘" -> 네이버. */
    private static String tailName(String c) {
        Matcher tail = TAIL_NAME.matcher(c.trim());
        if (!tail.find()) return null;
        String n = tail.group(1);
        for (String suffix : new String[]{"에다가", "에다", "으로", "로", "에"}) {
            if (n.endsWith(suffix) && n.length() - suffix.length() >= 2) {
                n = n.substring(0, n.length() - suffix.length());
                break;
            }
        }
        return n;
    }

    /** Particles and pronouns that end up in front of "폴더" without naming one. */
    private static final Set<String> NOT_NAMES = new LinkedHashSet<>(java.util.Arrays.asList(
            "을", "를", "이", "가", "은", "는", "에", "의", "내", "그", "이런", "저", "각", "각각", "새", "한", "다", "전부"));

    private static String explicitFolder(String c) {
        Matcher q = QUOTED.matcher(c);
        if (q.find()) return q.group(1).trim();
        Matcher f = FOLDER_NAME.matcher(c);
        String last = null;
        while (f.find()) {
            String n = f.group(1);
            // "크롬은 폴더에서 빼줘": a subject with its particle, not a folder name.
            if (!NOT_NAMES.contains(n) && !(n.length() > 1 && n.matches(".*(은|는|을|를|도)"))) last = n;
        }
        return last;
    }

    /** Folder name for a developer key: the Korean name when we know it. */
    private static String devFolder(String key) {
        for (Map.Entry<String, String> e : DEVELOPERS.entrySet()) if (e.getValue().equals(key)) return e.getKey();
        return key;
    }
}
