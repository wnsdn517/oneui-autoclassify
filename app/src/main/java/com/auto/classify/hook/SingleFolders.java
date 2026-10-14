package com.auto.classify.hook;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keeps apps-screen folders that hold a single app.
 *
 * On every start One UI Home loads each apps-screen folder by counting its children: none deletes
 * the folder, two or more build it, exactly one turns the folder into a plain app and deletes it
 * ("final item folder in apps"). When single-app folders are wanted (AI mode, or a minimum of 1 app
 * per folder), a one-child folder takes the "build it" path:
 * <ul>
 * <li>paged apps screen: the count is the size() of a CopyOnWriteArrayList of the folder's rows,
 *   checked inline; while the loader runs, such a list with one row that sits in a folder reports 2.
 *   The loader is run interpreted so that size() call reaches the hook.</li>
 * <li>vertical list: the converter (called with one child) and the builder are two methods of one
 *   signature; the converter is called with one child, and the builder runs instead.</li>
 * <li>folder destroy: when a folder icon finishes loading with fewer than two children, the folder
 *   is destroyed; skipped for exactly one child when the call comes from the verify step.</li>
 * </ul>
 *
 * The launcher's classes are found by DexKit fingerprints, not by obfuscated names: a string the
 * method uses (where the launcher has one) plus its parameter/return types and the shape of its
 * class. Each fingerprint must match exactly one place, otherwise that part stays off. Matches and
 * the first hits of each change are written to the diagnostic log.
 */
public final class SingleFolders {

    /** On when the settings want single-app folders. */
    public static volatile boolean enabled;

    private static final ThreadLocal<Boolean> inPagedLoad = new ThreadLocal<>();
    private static final Map<String, Integer> counts = new HashMap<>();

    private static final String ITEM_DATA = "com.honeyspace.sdk.database.entity.ItemData";
    private static final String DISPLAY_TYPE = "com.honeyspace.sdk.database.field.DisplayType";
    private static final String CONTAINER_TYPE = "com.honeyspace.sdk.database.field.ContainerType";
    private static final String CONTINUATION = "kotlin.coroutines.jvm.internal.ContinuationImpl";
    private static final String COW_LIST = "java.util.concurrent.CopyOnWriteArrayList";
    private static final String OBSERVABLE_LIST = "androidx.databinding.ObservableArrayList";

    private SingleFolders() {
    }

    /**
     * @param apkPath the launcher's APK (ApplicationInfo.sourceDir), searched by DexKit
     * @return problems found (empty when all three parts are covered)
     */
    public static List<String> install(ClassLoader cl, String apkPath) {
        List<String> problems = new ArrayList<>();
        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(apkPath);
            String p = paged(bridge, cl);
            if (p != null) problems.add("페이지형: " + p);
            String v = vertical(bridge, cl);
            if (v != null) problems.add("목록형: " + v);
            String f = folderView(bridge, cl);
            if (f != null) problems.add("폴더 표시: " + f);
        } catch (Throwable t) {
            problems.add("DexKit 실패: " + t);
        } finally {
            if (bridge != null) bridge.close();
        }
        return problems;
    }

    /** Paged loader: the one class with the item-loader signature (two of them: apps and folders). */
    private static String paged(DexKitBridge bridge, ClassLoader cl) {
        List<String> sig = Arrays.asList(ITEM_DATA, "java.util.List", "int", DISPLAY_TYPE, "boolean", CONTINUATION);
        Map<String, List<MethodData>> byClass = byClass(find(bridge, matcher().paramTypes(sig)));
        if (byClass.size() != 1) return "항목 로드 클래스 " + byClass.size() + "개 (1개여야 함)";
        Map.Entry<String, List<MethodData>> repo = byClass.entrySet().iterator().next();
        List<MethodData> loads = repo.getValue();
        if (loads.size() > 2) return repo.getKey() + " 로드 메서드 " + loads.size() + "개";
        try {
            Class<?> itemData = XposedHelpers.findClass(ITEM_DATA, cl);
            Class<?> containerType = XposedHelpers.findClass(CONTAINER_TYPE, cl);
            final Object folderContainer = XposedHelpers.getStaticObjectField(containerType, "FOLDER");
            final Method getContainerType = itemData.getMethod("getContainerType");
            final Set<String> loadNames = new HashSet<>();
            XC_MethodHook mark = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (enabled) inPagedLoad.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    inPagedLoad.remove();
                }
            };
            for (MethodData load : loads) {
                Method m = load.getMethodInstance(cl);
                if (!deoptimize(m)) return "이 Xposed 구현은 deoptimizeMethod를 지원하지 않음";
                loadNames.add(load.getClassName() + "." + load.getMethodName());
                XposedBridge.hookMethod(m, mark);
            }
            XposedBridge.hookMethod(CopyOnWriteArrayList.class.getMethod("size"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (inPagedLoad.get() == null || !Integer.valueOf(1).equals(param.getResult())) return;
                    try {
                        Object only = ((CopyOnWriteArrayList<?>) param.thisObject).get(0);
                        if (!itemData.isInstance(only) || getContainerType.invoke(only) != folderContainer) return;
                        // Only the folder count in the load method itself, not lists in code it calls.
                        if (!calledFrom(loadNames)) return;
                        param.setResult(2);
                        hit("페이지형 1개 폴더 유지");
                    } catch (Throwable t) {
                        fail("페이지형", t);
                    }
                }
            });
            Diag.log("단일 앱 폴더: 페이지형 " + repo.getKey() + " 연결 (로드 메서드 " + loads.size() + "개: "
                    + loadNames + ")");
            return null;
        } catch (Throwable t) {
            return "연결 실패: " + t;
        }
    }

    /**
     * Vertical list: the class with exactly two methods of one converter/builder signature and the
     * launcher's four-argument constructor. Children are argument 0 in the newer build, argument 1
     * (with a trailing boolean flag) in the older one.
     */
    private static String vertical(DexKitBridge bridge, ClassLoader cl) {
        String newer = verticalShape(bridge, cl, Arrays.asList(COW_LIST, ITEM_DATA, CONTINUATION), 0);
        if (newer == null) return null;
        String older = verticalShape(bridge, cl,
                Arrays.asList(ITEM_DATA, COW_LIST, CONTINUATION, "boolean"), 1);
        if (older == null) return null;
        return newer + " / " + older;
    }

    private static String verticalShape(DexKitBridge bridge, ClassLoader cl, List<String> sig, int childrenArg) {
        Map<String, List<MethodData>> byClass = byClass(find(bridge, matcher().paramTypes(sig)));
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, List<MethodData>> e : byClass.entrySet()) {
            if (e.getValue().size() == 2) candidates.add(e.getKey());
        }
        if (candidates.size() != 1) return "후보 " + candidates.size() + "개 (1개여야 함)";
        String loaderName = candidates.get(0);
        try {
            Class<?> loader = XposedHelpers.findClass(loaderName, cl);
            Class<?> cont = XposedHelpers.findClass(CONTINUATION, cl);
            // The loader's constructor is part of the fingerprint too.
            Constructor<?> ctor = XposedHelpers.findConstructorExactIfExists(loader,
                    XposedHelpers.findClassIfExists("com.honeyspace.sdk.database.HoneyDataSource", cl),
                    XposedHelpers.findClassIfExists("com.honeyspace.ui.common.model.AppItemCreator", cl),
                    XposedHelpers.findClassIfExists("com.honeyspace.ui.common.model.HiddenEventOperator", cl),
                    XposedHelpers.findClassIfExists("com.honeyspace.ui.common.model.IconItemDataCreator", cl));
            if (ctor == null) return loaderName + " 생성자 모양이 다름";

            List<Method> paths = new ArrayList<>();
            for (MethodData d : byClass.get(loaderName)) paths.add(d.getMethodInstance(cl));
            Field flag = null;
            for (Field f : loader.getDeclaredFields()) {
                if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) {
                    if (flag != null) return loaderName + " 플래그가 여러 개";
                    flag = f;
                }
            }
            if (flag != null) flag.setAccessible(true);
            final Field readOnly = flag;
            // The caller is compiled with the converter inlined, which would bypass the hook.
            for (Method m : loader.getDeclaredMethods()) if (!Modifier.isAbstract(m.getModifiers())) deoptimize(m);
            for (Method m : paths) {
                final Method other = paths.get(0) == m ? paths.get(1) : paths.get(0);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled) return;
                        List<?> children = (List<?>) param.args[childrenArg];
                        if (children == null || children.size() != 1) return;
                        // The flag marks a loader that only reads (it never deletes); leave that one alone.
                        if (readOnly != null && readOnly.getBoolean(param.thisObject)) return;
                        try {
                            param.setResult(XposedBridge.invokeOriginalMethod(other, param.thisObject, param.args));
                            hit("목록형 1개 폴더 유지");
                        } catch (Throwable t) {
                            // Let the launcher's own conversion run rather than break loading.
                            fail("목록형", t);
                        }
                    }
                });
            }
            Diag.log("단일 앱 폴더: 목록형 " + loaderName + " 연결 (플래그 "
                    + (readOnly == null ? "없음" : readOnly.getName()) + ", 자식 인자 " + childrenArg + ")");
            return null;
        } catch (Throwable t) {
            return loaderName + " 연결 실패: " + t;
        }
    }

    /**
     * Folder destroy: the one method that logs "destroyFolder animate" (void, one boolean). Its children
     * list is the ObservableArrayList field it uses. Skipped for exactly one child when the caller is the
     * one that logs "verifyData remove folder".
     */
    private static String folderView(DexKitBridge bridge, ClassLoader cl) {
        List<MethodData> destroys = find(bridge, matcher()
                .addUsingString("destroyFolder animate", StringMatchType.StartsWith, false)
                .returnType("void")
                .paramTypes(Collections.singletonList("boolean")));
        if (destroys.size() != 1) return "폴더 제거 메서드 " + destroys.size() + "개 (1개여야 함)";
        MethodData destroyData = destroys.get(0);

        List<FieldData> lists = new ArrayList<>();
        for (UsingFieldData used : destroyData.getUsingFields()) {
            FieldData f = used.getField();
            if (OBSERVABLE_LIST.equals(f.getTypeName()) && destroyData.getClassName().equals(f.getDeclaredClassName())) {
                lists.add(f);
            }
        }
        if (lists.size() != 1) return "폴더 항목 목록 필드 " + lists.size() + "개 (1개여야 함)";

        List<MethodData> callers = find(bridge, matcher()
                .addUsingString("verifyData remove folder", StringMatchType.StartsWith, false));
        if (callers.isEmpty()) return "검증 호출자 없음";
        final Set<String> verify = new HashSet<>();
        for (MethodData c : callers) verify.add(c.getClassName() + "." + c.getMethodName());

        try {
            Method destroy = destroyData.getMethodInstance(cl);
            final Field children = lists.get(0).getFieldInstance(cl);
            children.setAccessible(true);
            XposedBridge.hookMethod(destroy, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!enabled) return;
                    List<?> items = (List<?>) children.get(param.thisObject);
                    if (items == null || items.size() != 1 || !calledFrom(verify)) return;
                    hit("폴더 제거 보류 (앱 1개)");
                    param.setResult(null);
                }
            });
            Diag.log("단일 앱 폴더: 폴더 표시 " + destroyData.getClassName() + "." + destroyData.getMethodName()
                    + " 연결 (검증 호출자 " + verify + ")");
            return null;
        } catch (Throwable t) {
            return "연결 실패: " + t;
        }
    }

    private static MethodMatcher matcher() {
        return MethodMatcher.Companion.create();
    }

    private static List<MethodData> find(DexKitBridge bridge, MethodMatcher matcher) {
        return new ArrayList<>(bridge.findMethod(FindMethod.Companion.create().matcher(matcher)));
    }

    private static Map<String, List<MethodData>> byClass(List<MethodData> methods) {
        Map<String, List<MethodData>> byClass = new LinkedHashMap<>();
        for (MethodData m : methods) {
            byClass.computeIfAbsent(m.getClassName(), k -> new ArrayList<>()).add(m);
        }
        return byClass;
    }

    /**
     * True when the nearest launcher frame is one of [methods] ("pkg.Class.name"). Skipped: this module,
     * the JDK, and the hook framework (its classes have no package or sit beside XposedBridge).
     */
    private static boolean calledFrom(Set<String> methods) {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (!c.contains(".") || c.startsWith("java.") || c.startsWith("dalvik.") || c.startsWith("de.robv.")
                    || c.startsWith("com.auto.classify.") || c.startsWith("org.lsposed.") || c.contains("XposedBridge")) continue;
            return methods.contains(c + "." + e.getMethodName());
        }
        return false;
    }

    /** Runs the method interpreted (LSPosed/Vector API), so calls inside it reach hooks. */
    private static boolean deoptimize(Member m) {
        try {
            XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class).invoke(null, m);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Logs the first few hits of each behaviour change, so the log shows the hooks are doing something. */
    private static synchronized void hit(String what) {
        int n = counts.merge(what, 1, Integer::sum);
        if (n <= 3) Diag.log("단일 앱 폴더: " + what + " (" + n + "번째)");
    }

    /** A hook threw while changing behaviour. Logged each time for three; the third raises a diagnosis error. */
    private static synchronized void fail(String what, Throwable t) {
        int n = counts.merge("실패 " + what, 1, Integer::sum);
        if (n <= 3) Diag.log("단일 앱 폴더 오류 (" + what + ", " + n + "번째): " + t);
        if (n == 3) {
            Diag.check("hook.single.runtime." + what, Diag.F_ORGANIZE, false, Diag.ERROR,
                    "앱 1개 폴더 " + what + " 훅이 반복해서 실패해요: " + t);
        }
    }
}
