package com.auto.classify.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keeps apps-screen folders that hold a single app.
 *
 * On every start One UI Home (17.0.07.13) loads each apps-screen folder by counting its
 * children: none deletes the folder, two or more build it, exactly one turns the folder into a
 * plain app and deletes it ("final item folder in apps"). When single-app folders are wanted
 * (AI mode, or a minimum of 1 app per folder), a one-child folder takes the "build it" path:
 * <ul>
 * <li>paged apps screen (S2.n, method z): the count is the size() of a CopyOnWriteArrayList of
 *   the folder's rows, checked inline; while z runs on a thread, such a list with one row that
 *   sits in a folder reports 2. z is run interpreted so that size() call reaches the hook.</li>
 * <li>vertical list (k6.k): the converter and the builder are two methods of one signature; the
 *   converter is the one called with one child, and the builder runs instead.</li>
 * </ul>
 * Each part checks the shapes it relies on and stays off when they differ.
 */
public final class SingleFolders {

    /** On when the settings want single-app folders. */
    public static volatile boolean enabled;

    private static final ThreadLocal<Boolean> inPagedLoad = new ThreadLocal<>();

    private SingleFolders() {
    }

    /** @return problems found (empty when both loaders are covered) */
    public static List<String> install(ClassLoader cl) {
        List<String> problems = new ArrayList<>();
        String p = paged(cl);
        if (p != null) problems.add("페이지형: " + p);
        String v = vertical(cl);
        if (v != null) problems.add("목록형: " + v);
        String f = folderView(cl);
        if (f != null) problems.add("폴더 표시: " + f);
        return problems;
    }

    private static String paged(ClassLoader cl) {
        try {
            Class<?> repo = XposedHelpers.findClassIfExists("S2.n", cl);
            if (repo == null) return "앱스 화면 저장소 클래스 없음";
            Class<?> itemData = XposedHelpers.findClass("com.honeyspace.sdk.database.entity.ItemData", cl);
            Class<?> displayType = XposedHelpers.findClass("com.honeyspace.sdk.database.field.DisplayType", cl);
            Class<?> cont = XposedHelpers.findClass("kotlin.coroutines.jvm.internal.ContinuationImpl", cl);
            // Item loaders of this shape: one for apps, one for folders (only the folder one counts children).
            List<Method> loads = new ArrayList<>();
            for (Method m : repo.getDeclaredMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 6 && t[0] == itemData && t[1] == List.class && t[2] == int.class && t[3] == displayType
                        && t[4] == boolean.class && t[5] == cont && !Modifier.isStatic(m.getModifiers())) loads.add(m);
            }
            if (loads.isEmpty() || loads.size() > 2) return "항목 로드 메서드 " + loads.size() + "개";
            Class<?> containerType = XposedHelpers.findClass("com.honeyspace.sdk.database.field.ContainerType", cl);
            final Object folderContainer = XposedHelpers.getStaticObjectField(containerType, "FOLDER");
            final Method getContainerType = itemData.getMethod("getContainerType");
            final java.util.Set<String> loadNames = new java.util.HashSet<>();
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
            for (Method load : loads) {
                if (!deoptimize(load)) return "이 Xposed 구현은 deoptimizeMethod를 지원하지 않음";
                loadNames.add(repo.getName() + "." + load.getName());
                XposedBridge.hookMethod(load, mark);
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
                    } catch (Throwable ignored) {
                    }
                }
            });
            return null;
        } catch (Throwable t) {
            return "연결 실패: " + t;
        }
    }

    /**
     * When a folder icon finishes loading, its "verifyData" step (d4.c) destroys a folder with
     * fewer than two children (h4.Z.n). Skipped for exactly one child; other callers (the user
     * deleting a folder, dragging apps out) are left alone.
     */
    private static String folderView(ClassLoader cl) {
        try {
            Class<?> vm = XposedHelpers.findClassIfExists("h4.Z", cl);
            if (vm == null) return "폴더 뷰모델 클래스 없음";
            Method destroy = XposedHelpers.findMethodExactIfExists(vm, "n", boolean.class);
            if (destroy == null || destroy.getReturnType() != void.class) return "폴더 제거 메서드 없음";
            Class<?> observable = XposedHelpers.findClassIfExists("androidx.databinding.ObservableArrayList", cl);
            final Field children;
            try {
                children = vm.getDeclaredField("f");
            } catch (NoSuchFieldException e) {
                return "폴더 항목 목록 없음";
            }
            if (observable == null || children.getType() != observable) return "폴더 항목 목록 모양이 다름";
            children.setAccessible(true);
            final java.util.Set<String> verify = java.util.Collections.singleton("d4.c.invoke");
            XposedBridge.hookMethod(destroy, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!enabled) return;
                    List<?> items = (List<?>) children.get(param.thisObject);
                    if (items == null || items.size() != 1 || !calledFrom(verify)) return;
                    param.setResult(null);
                }
            });
            return null;
        } catch (Throwable t) {
            return "연결 실패: " + t;
        }
    }

    private static String vertical(ClassLoader cl) {
        try {
            Class<?> loader = XposedHelpers.findClassIfExists("k6.k", cl);
            if (loader == null) return "로더 클래스 없음";
            Class<?> itemData = XposedHelpers.findClass("com.honeyspace.sdk.database.entity.ItemData", cl);
            Class<?> cont = XposedHelpers.findClass("kotlin.coroutines.jvm.internal.ContinuationImpl", cl);
            // Shape check, so a different class under the same name on another version is never touched.
            Constructor<?> ctor = XposedHelpers.findConstructorExactIfExists(loader,
                    XposedHelpers.findClassIfExists("com.honeyspace.sdk.database.HoneyDataSource", cl),
                    XposedHelpers.findClassIfExists("com.honeyspace.ui.common.model.AppItemCreator", cl),
                    XposedHelpers.findClassIfExists("com.honeyspace.ui.common.model.HiddenEventOperator", cl),
                    XposedHelpers.findClassIfExists("com.honeyspace.ui.common.model.IconItemDataCreator", cl));
            if (ctor == null) return "로더 모양이 다름";
            List<Method> paths = new ArrayList<>();
            for (Method m : loader.getDeclaredMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 4 && t[0] == itemData && t[1] == CopyOnWriteArrayList.class
                        && t[2] == cont && t[3] == boolean.class && !Modifier.isStatic(m.getModifiers())) paths.add(m);
            }
            if (paths.size() != 2) return "폴더 처리 메서드 " + paths.size() + "개 (2개여야 함)";
            Field flag = null;
            for (Field f : loader.getDeclaredFields()) {
                if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) {
                    if (flag != null) return "로더 플래그가 여러 개";
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
                        List<?> children = (List<?>) param.args[1];
                        if (children == null || children.size() != 1) return;
                        // The flag marks a loader that only reads (it never deletes); leave that one alone.
                        if (readOnly != null && readOnly.getBoolean(param.thisObject)) return;
                        try {
                            param.setResult(XposedBridge.invokeOriginalMethod(other, param.thisObject, param.args));
                        } catch (Throwable t) {
                            // Let the launcher's own conversion run rather than break loading.
                            Diag.log("앱 1개 폴더 유지 실패, 원래대로 처리: " + t);
                        }
                    }
                });
            }
            return null;
        } catch (Throwable t) {
            return "연결 실패: " + t;
        }
    }

    /**
     * True when the nearest launcher frame is one of [methods] ("pkg.Class.name"). Skipped: this module,
     * the JDK, and the hook framework (its classes have no package or sit beside XposedBridge).
     */
    private static boolean calledFrom(java.util.Set<String> methods) {
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
}
