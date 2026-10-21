package com.auto.classify.hook;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.auto.classify.core.Config;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Liquid Glass folders, for the closed tile and the opened panel.
 * <ul>
 * <li>Tile: the launcher draws each folder's background into a bitmap (createBg, or createBackgroundBitmap
 * for the drag highlight); its alpha is the tile shape. It is replaced by a nearly clear gradient with a thin
 * bright rim. Under the folder's icons a lens pass (AGSL) draws the wallpaper behind the folder, bent toward
 * the edges like a thick piece of glass.</li>
 * <li>Panel: the opened folder's ColorBackground view skips its flat color and gets the same lens pass.</li>
 * </ul>
 * The wallpaper is looked up by the view's position on screen, and a pre-draw listener redraws a lens whenever
 * its view has moved, so page swipes and open/close animations keep the backdrop in step.
 */
final class FolderLook {

    private static final long TTL_MS = 30_000;
    private static final String[] HOLDERS = {
            "com.honeyspace.ui.common.iconview.FolderIconSupplier$Companion",
            "com.honeyspace.ui.common.folder.FolderPreviewDrawerImpl",
    };
    private static final String[] CONTAINERS = {
            "com.honeyspace.ui.honeypots.folder.presentation.OpenFullFolderContainer",
            "com.honeyspace.ui.honeypots.folder.presentation.open.OpenFullFolderContainer",
    };
    private static final String[] PANELS = {
            "com.honeyspace.ui.honeypots.folder.presentation.ColorBackground",
            "com.honeyspace.ui.honeypots.folder.presentation.open.ColorBackground",
            "com.honeyspace.ui.honeypots.folder.presentation.OpenFolderFRView",
            "com.honeyspace.ui.honeypots.folder.presentation.open.OpenFolderFRView",
    };

    private final Context context;
    private final ClassLoader cl;
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicInteger seen = new AtomicInteger();
    private static final ThreadLocal<Boolean> inCreateBg = new ThreadLocal<>();
    private final Map<View, Watch> watched = new WeakHashMap<>();
    private final AtomicBoolean wallLoading = new AtomicBoolean();
    private volatile boolean glass;
    private volatile long loadedAt;
    private volatile Bitmap wall;
    private volatile Bitmap wallSoft;
    private volatile Bitmap shot;
    private final Map<String, String> shaders = new java.util.concurrent.ConcurrentHashMap<>();
    private Class<?> folderIconView;
    private final java.util.List<Class<?>> panelViews = new java.util.ArrayList<>();

    FolderLook(Context context, ClassLoader cl) {
        this.context = context;
        this.cl = cl;
    }

    void install() {
        int hooked = 0;
        for (String name : HOLDERS) {
            Class<?> holder = findClass(name);
            if (holder == null) continue;
            hooked += hookBitmap(holder, "createBackgroundBitmap", false);
        }
        // Every folder icon on the apps screen and home goes through createBg; the tile is drawn from there.
        Class<?> supplier = findClass("com.honeyspace.ui.common.iconview.FolderIconSupplier");
        if (supplier != null) hooked += hookBitmap(supplier, "createBg", true);
        if (hooked == 0) Diag.log("폴더 Liquid Glass: 폴더 배경 그리는 곳을 찾지 못해 건너뜀");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            hookTileLens();
            hookPanel();
            hookCaptureExclusion();
            hookContainer();
            hookClickCapture();
            hookKeepHome();
        } else {
            Diag.log("폴더 Liquid Glass: 렌즈 효과는 Android 13 이상에서만");
        }
        loading.set(true);
        load();
    }

    private Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- tile bitmap --------------------------------------------------------------------------------

    private int hookBitmap(Class<?> holder, String method, boolean outer) {
        int n = 0;
        for (java.lang.reflect.Method m : holder.getDeclaredMethods()) {
            if (!m.getName().equals(method) || m.getReturnType() != Bitmap.class) continue;
            try {
                XposedBridge.class.getDeclaredMethod("deoptimizeMethod", java.lang.reflect.Member.class).invoke(null, m);
            } catch (Throwable ignored) {
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (outer) inCreateBg.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (outer) inCreateBg.remove();
                    else if (inCreateBg.get() != null) return;
                    refreshIfStale();
                    if (seen.incrementAndGet() <= 4) {
                        Diag.log("폴더 배경 " + method + ": 유리=" + glass + " 인자=" + param.args.length);
                    }
                    if (!glass || !(param.getResult() instanceof Bitmap)) return;
                    Boolean useDefault = null;
                    Integer color = null;
                    for (Object a : param.args) {
                        if (useDefault == null && a instanceof Boolean) useDefault = (Boolean) a;
                        if (color == null && a instanceof Integer) color = (Integer) a;
                    }
                    if (!Boolean.TRUE.equals(useDefault) || color == null) return;
                    try {
                        Bitmap g = glassOf((Bitmap) param.getResult(), outer ? color : (Integer) param.args[4]);
                        if (g != null) param.setResult(g);
                    } catch (Throwable t) {
                        Diag.failed(Diag.F_CORE, "폴더 Liquid Glass", t);
                    }
                }
            });
            n++;
            Diag.log("폴더 Liquid Glass 훅 연결 (" + holder.getName() + "." + method + ")");
        }
        return n;
    }

    /** The launcher's tile is replaced by a clear bitmap of the same size; the lens pass draws the glass. */
    static Bitmap glassOf(Bitmap tile, int bgColor) {
        int w = tile.getWidth(), h = tile.getHeight();
        if (w < 8 || h < 8) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setDensity(tile.getDensity());
        return out;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ---- lens under the tile's icons ----------------------------------------------------------------

    private void hookTileLens() {
        folderIconView = findClass("com.honeyspace.ui.common.iconview.FolderIconViewImpl");
        Class<?> base = findClass("com.honeyspace.ui.common.iconview.IconViewImpl");
        if (folderIconView == null || base == null) {
            Diag.log("폴더 Liquid Glass: 폴더 아이콘 뷰를 찾지 못해 렌즈 효과는 건너뜀");
            return;
        }
        XposedBridge.hookAllMethods(base, "onDraw", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!glass || capturing || !folderIconView.isInstance(param.thisObject)) return;
                try {
                    TextView v = (TextView) param.thisObject;
                    Drawable icon = v.getCompoundDrawables()[1];
                    if (icon == null) return;
                    int size = icon.getBounds().width();
                    if (size <= 0) return;
                    float left = (v.getWidth() + v.getPaddingLeft() - v.getPaddingRight() - size) / 2f;
                    float inset = size * 0.02f;
                    lens(v, (Canvas) param.args[0], left + inset, v.getPaddingTop() + inset,
                            size - 2 * inset, size - 2 * inset, size * 0.33f, size * 0.14f, size * 0.32f, 0f, 0.10f, false);
                } catch (Throwable t) {
                    Diag.failed(Diag.F_CORE, "폴더 렌즈", t);
                }
            }
        });
        Diag.log("폴더 Liquid Glass 렌즈 훅 연결");
    }

    // ---- picture of the screen behind the opened folder ---------------------------------------------------

    private static volatile boolean capturing;
    private static volatile View captureExclude;
    private static volatile boolean captureTiles;
    private volatile int blurLevel = 30;
    private volatile Bitmap appsShot;
    private volatile long appsShotAt;
    private volatile long shotAt;

    /** While the screen is being captured, the opened folder itself is left out of the picture. */
    private void hookCaptureExclusion() {
        XposedBridge.hookAllMethods(android.view.ViewGroup.class, "drawChild", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!capturing || param.args.length < 2) return;
                if (param.args[1] == captureExclude
                        || (captureTiles && folderIconView != null && folderIconView.isInstance(param.args[1]))) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });
    }

    /** Three box passes in each direction: a smooth Gaussian-like blur that keeps the bitmap's resolution. */
    static Bitmap blur(Bitmap src, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        int[] b = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            box(a, b, w, h, radius, true);
            box(b, a, w, h, radius, false);
        }
        src.recycle();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.min(len - 1, Math.max(0, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | (sr / div << 16) | (sg / div << 8) | (sb / div);
                int add = in[base + Math.min(len - 1, i + r + 1) * step];
                int sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /**
     * The whole screen behind an opened folder is blurred, as on iOS: the container paints the blurred picture of the
     * screen over everything under it before it draws the panel.
     */
    private void hookClickCapture() {
        XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!glass || capturing || wall == null || folderIconView == null
                        || !folderIconView.isInstance(param.thisObject)) return;
                // A tap that lands while a folder is opening sees a screen that is already fading: keep the good picture.
                if (folderShowing() && shot != null) return;
                clickAt = android.os.SystemClock.uptimeMillis();
                refreshNow();
                try {
                    captureScreen((View) param.thisObject, (View) param.thisObject);
                } catch (Throwable t) {
                    capturing = false;
                    captureExclude = null;
                    Diag.failed(Diag.F_CORE, "폴더 뒤 화면", t);
                }
            }
        });
    }

    private static volatile long clickAt;
    private static volatile long lastDrawAt;

    private static volatile java.lang.ref.WeakReference<View> openContainer;
    private static final Map<String, Float> pageAlpha = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One UI fades the home page and the dock out when a folder opens and back in when it closes, which is what
     * flickers once the blurred screen sits behind the folder. They stay put instead: the blurred picture covers them.
     */
    private void hookKeepHome() {
        XposedBridge.hookAllMethods(View.class, "setAlpha", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!glass || capturing || param.args.length != 1 || !(param.args[0] instanceof Float)) return;
                String n = param.thisObject.getClass().getName();
                boolean page = n.contains("WorkspaceContainer") || n.contains("ApplistContainer");
                if (!page && !n.contains("HotseatCellLayout") && !n.contains("AppsSearchBar")) return;
                boolean showing = folderShowing();
                if (!showing) pageAlpha.put(n, (Float) param.args[0]);
                // Only a home page that was on screen stays: from the apps screen the home is hidden and stays hidden.
                Float before = pageAlpha.get(n);
                if (showing && before != null && before > 0.5f && (Float) param.args[0] < 1f) param.args[0] = 1f;
            }
        });
    }

    private static boolean folderShowing() {
        if (android.os.SystemClock.uptimeMillis() - clickAt < 1500) return true;
        java.lang.ref.WeakReference<View> ref = openContainer;
        View c = ref == null ? null : ref.get();
        return c != null && c.isAttachedToWindow() && c.getVisibility() == View.VISIBLE && c.getAlpha() > 0.01f;
    }

    private void hookContainer() {
        final java.util.List<Class<?>> containers = new java.util.ArrayList<>();
        for (String name : CONTAINERS) {
            Class<?> c = findClass(name);
            if (c != null) containers.add(c);
        }
        if (containers.isEmpty()) return;
        // Drawn by the container's parent so the picture is not held back by the container's own fade: the blur starts
        // with the opening instead of arriving at the end of it.
        XposedBridge.hookAllMethods(ViewGroup.class, "drawChild", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!glass || capturing || param.args.length < 2 || !(param.args[0] instanceof Canvas)) return;
                boolean match = false;
                for (Class<?> c : containers) if (c.isInstance(param.args[1])) match = true;
                if (!match) return;
                View v = (View) param.args[1];
                View parent = (View) param.thisObject;
                try {
                    long now = android.os.SystemClock.uptimeMillis();
                    boolean opening = now - clickAt < 700;
                    float shown = v.getVisibility() == View.VISIBLE ? v.getAlpha() : 0f;
                    if (wall == null || v.getWidth() == 0 || v.getVisibility() != View.VISIBLE
                            || (shown < 0.02f && !opening)) return;
                    // A folder that did not open from a tap on its icon: this is the first frame of the opening, while
                    // the page behind is still almost fully visible, so the screen is taken here.
                    if (now - lastDrawAt > 800 && now - clickAt > 3000) {
                        clickAt = now;
                        refreshNow();
                        captureScreen(v, v);
                    }
                    lastDrawAt = now;
                    Bitmap s = shot;
                    if (s == null) return;
                    openContainer = new java.lang.ref.WeakReference<>(v);
                    Watch w = watch(v);
                    w.parentToo = true;
                    long t = now - clickAt;
                    float ramp = Math.min(1f, t / 220f);
                    float alpha = opening ? Math.max(shown, ramp) : shown;
                    Matrix toLocal = new Matrix();
                    if (!toScreen(parent).invert(toLocal)) return;
                    android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    paint.setAlpha(Math.round(255 * alpha));
                    Canvas cv = (Canvas) param.args[0];
                    cv.save();
                    cv.concat(toLocal);
                    cv.drawBitmap(s, null, new android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels), paint);
                    cv.restore();
                    if (ramp < 1f) parent.postInvalidateOnAnimation();
                } catch (Throwable t) {
                    capturing = false;
                    captureExclude = null;
                    Diag.failed(Diag.F_CORE, "폴더 뒤 블러", t);
                }
            }
        });
    }

    /** The opened folder's container: the outermost ancestor whose class is a folder container. */
    private static View containerOf(View v) {
        View found = v;
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().contains("FolderContainer")) found = (View) p;
        }
        return found;
    }

    /**
     * What Apple does when a folder opens: the screen behind it, blurred, becomes the glass's backdrop. The wallpaper
     * is drawn first (it is not a view), then the launcher's own views (the page with its icons and widgets) minus the
     * folder. Taken once per opening at a quarter of the resolution, then shrunk further, which is the blur.
     */
    /** The launcher's views at a quarter of the resolution over the wallpaper; null when it cannot be taken now. */
    private Bitmap snapshot(View v, View exclude, boolean tiles) {
        android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
        View root = v.getRootView();
        if (root == null || capturing) return null;
        int w = Math.max(8, dm.widthPixels / 4), h = Math.max(8, dm.heightPixels / 4);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        // The apps screen shows the wallpaper heavily blurred, not as it is on the home screen.
        Bitmap bgWall = dimFor(v) < 1f && wallSoft != null ? wallSoft : wall;
        if (bgWall != null) {
            Paint base = new Paint(Paint.FILTER_BITMAP_FLAG);
            // The apps screen dims the wallpaper with a layer that is not a view, so it is dimmed here too.
            float dim = dimFor(v);
            if (dim < 1f) {
                ColorMatrix cm = new ColorMatrix();
                cm.setScale(dim, dim, dim, 1f);
                base.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            }
            cv.drawBitmap(bgWall, null, new android.graphics.Rect(0, 0, w, h), base);
        }
        cv.save();
        cv.scale(0.25f, 0.25f);
        capturing = true;
        captureExclude = exclude;
        captureTiles = tiles;
        try {
            root.draw(cv);
        } finally {
            capturing = false;
            captureExclude = null;
            captureTiles = false;
        }
        cv.restore();
        return bm;
    }

    private void captureScreen(View v, View exclude) {
        Bitmap bm = snapshot(v, exclude, false);
        if (bm == null) return;
        // The strength setting is the apps screen's; the home screen keeps the default.
        int level = dimFor(v) < 1f ? blurLevel : 30;
        Bitmap soft = blur(bm, 2 + Math.round(level * 0.2f));
        shot = soft;
        shotAt = android.os.SystemClock.uptimeMillis();
        if (seen.incrementAndGet() <= 10) {
            Diag.log("폴더 뒤 화면 캡처 " + soft.getWidth() + "x" + soft.getHeight() + " 흐림 " + level + (dimFor(v) < 1f ? " 앱" : " 홈"));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "shot.png"))) {
                soft.compress(Bitmap.CompressFormat.PNG, 90, out);
            } catch (Throwable ignored) {
            }
        }
    }

    /** The apps screen's own background (wallpaper, dimming, search bar) without the folders: what the tiles bend. */
    private void captureApps(View v) {
        Bitmap bm = snapshot(v, null, true);
        if (bm == null) return;
        appsShot = blur(bm, 2 + Math.round(blurLevel * 0.2f));
        appsShotAt = android.os.SystemClock.uptimeMillis();
        Diag.log("앱 화면 배경 캡처");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (View w : new java.util.ArrayList<>(watched.keySet())) w.invalidate();
        });
    }

    // ---- opened folder panel ------------------------------------------------------------------------

    private void hookPanel() {
        for (String name : PANELS) {
            Class<?> c = findClass(name);
            if (c != null) panelViews.add(c);
        }
        if (panelViews.isEmpty()) {
            Diag.log("폴더 Liquid Glass: 열린 폴더 배경 뷰를 찾지 못해 건너뜀");
            return;
        }
        // The panel's background is set from code (setBackground); a lens drawable takes its place.
        try {
            XposedBridge.hookAllMethods(View.class, "setBackground", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!glass || param.args.length != 1 || param.args[0] instanceof LensDrawable
                            || !isPanel(param.thisObject)) return;
                    param.args[0] = new LensDrawable();
                    if (seen.incrementAndGet() <= 6) {
                        Diag.log("폴더 열림 배경 교체: " + param.thisObject.getClass().getSimpleName());
                    }
                }
            });
            Diag.log("폴더 Liquid Glass 열린 폴더 훅 연결 (" + panelViews + ")");
        } catch (Throwable t) {
            Diag.log("폴더 Liquid Glass: 열린 폴더 훅 실패 " + t);
        }
    }

    /** The opened panel's background: the lens over the wallpaper, sized to the view it is set on. */
    private final class LensDrawable extends Drawable {
        @Override
        public void draw(Canvas canvas) {
            if (capturing || !(getCallback() instanceof View)) return;
            View v = (View) getCallback();
            try {
                Outline o = new Outline();
                if (v.getOutlineProvider() != null) v.getOutlineProvider().getOutline(v, o);
                float w = v.getWidth(), h = v.getHeight();
                float r = o.getRadius() > 0 ? o.getRadius() : Math.min(w, h) * 0.08f;
                float d = v.getResources().getDisplayMetrics().density;
                lens(v, canvas, 0, 0, w, h, r, 30 * d, 60 * d, 0.82f, 0.18f, true);
            } catch (Throwable t) {
                Diag.failed(Diag.F_CORE, "폴더 열림 렌즈", t);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private boolean isPanel(Object o) {
        for (Class<?> c : panelViews) if (c.isInstance(o)) return true;
        return false;
    }

    // ---- lens ---------------------------------------------------------------------------------------

    /** Remembers where a lens view was drawn so a move can be noticed before the next frame. */
    private static final class Watch implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final java.lang.ref.WeakReference<View> view;
        final float[] mat = new float[9];
        final float dim;
        volatile boolean parentToo;
        float alpha = -1f;

        Watch(View view, float dim) {
            this.view = new java.lang.ref.WeakReference<>(view);
            this.dim = dim;
        }

        @Override
        public boolean onPreDraw() {
            View v = view.get();
            if (v == null) return false;
            float[] now = new float[9];
            toScreen(v).getValues(now);
            boolean moved = !java.util.Arrays.equals(now, mat);
            float a = v.getAlpha();
            boolean faded = parentToo && a != alpha;
            alpha = a;
            if (moved || faded) {
                v.invalidate();
                if (parentToo && v.getParent() instanceof View) ((View) v.getParent()).invalidate();
            }
            return true;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            v.getViewTreeObserver().removeOnPreDrawListener(this);
        }
    }

    /**
     * Where the view's own coordinates land on the screen, scale and rotation included. The opening animation scales
     * the panel, so a lens that only knew the position would drag its backdrop along with the panel.
     */
    static Matrix toScreen(View v) {
        Matrix m = new Matrix();
        chain(v, m);
        return m;
    }

    private static void chain(View v, Matrix m) {
        ViewParent p = v.getParent();
        if (p instanceof View) {
            View pv = (View) p;
            chain(pv, m);
            m.preTranslate(-pv.getScrollX(), -pv.getScrollY());
        }
        m.preTranslate(v.getLeft(), v.getTop());
        if (!v.getMatrix().isIdentity()) m.preConcat(v.getMatrix());
    }

    private Watch watch(View v) {
        Watch w = watched.get(v);
        if (w == null) {
            w = new Watch(v, dimFor(v));
            watched.put(v, w);
            v.getViewTreeObserver().addOnPreDrawListener(w);
            v.addOnAttachStateChangeListener(w);
        }
        return w;
    }

    /** The apps screen shows a dimmed wallpaper, so the lens is dimmed there too. */
    private static float dimFor(View v) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            String n = p.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("applist") || n.contains("allapps") || n.contains("appscreen")) return 0.72f;
        }
        return 1f;
    }

    /** The Kyant0 shaders are read from this module's assets (the launcher process has no copy of them). */
    private String shaderSource(String asset) {
        String cached = shaders.get(asset);
        if (cached != null) return cached;
        try {
            Context mod = context.createPackageContext(Config.MODULE_PKG, 0);
            try (java.io.InputStream in = mod.getAssets().open(asset)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                String src = out.toString("UTF-8");
                shaders.put(asset, src);
                return src;
            }
        } catch (Throwable t) {
            Diag.log("폴더 Liquid Glass: " + asset + " 읽기 실패 " + t);
            return null;
        }
    }

    /**
     * Kyant0/AndroidLiquidGlass over the wallpaper: vibrancy (saturation 1.5), then the lens (refraction with
     * dispersion and depth effect), a translucent surface, and the default edge highlight (45 degrees, falloff 2).
     * Heights follow its demos: a bezel of a quarter of the short side, refracting by twice its height.
     *
     * @param dimOverride 0 = decide from where the view sits, otherwise the brightness to use
     */
    private void lens(View v, Canvas canvas, float left, float top, float w, float h, float radius,
                      float refractionHeight, float refractionAmount, float dimOverride, float surfaceAlpha,
                      boolean frosted) {
        android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
        Watch watch = watch(v);
        if (wall == null) {
            loadWallpaper(dm.widthPixels, dm.heightPixels);
            return;
        }
        String refraction = shaderSource("kyant_refraction.agsl");
        String highlight = shaderSource("kyant_highlight.agsl");
        if (refraction == null || highlight == null) return;
        Matrix toScreen = toScreen(v);
        toScreen.getValues(watch.mat);
        Matrix toLocal = new Matrix();
        if (!toScreen.invert(toLocal)) return;
        Bitmap real = frosted ? shot : null;
        boolean appsBg = false;
        if (!frosted && watch.dim < 1f && !capturing) {
            if (appsShot == null || android.os.SystemClock.uptimeMillis() - appsShotAt > 60_000) {
                try {
                    captureApps(v);
                } catch (Throwable t) {
                    capturing = false;
                    captureExclude = null;
                    captureTiles = false;
                    Diag.failed(Diag.F_CORE, "앱 화면 배경", t);
                }
            }
            real = appsShot;
            appsBg = real != null;
        }
        Bitmap bg = real != null ? real : ((frosted || watch.dim < 1f) && wallSoft != null ? wallSoft : wall);

        BitmapShader content = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        content.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        Matrix m = new Matrix();
        float k = (float) dm.widthPixels / bg.getWidth();
        m.postScale(k, k);
        m.postConcat(toLocal);
        content.setLocalMatrix(m);

        RuntimeShader lens = new RuntimeShader(refraction);
        lens.setInputShader("content", content);
        lens.setFloatUniform("size", w, h);
        lens.setFloatUniform("offset", -left, -top);
        lens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        lens.setFloatUniform("refractionHeight", refractionHeight);
        lens.setFloatUniform("refractionAmount", -refractionAmount);
        lens.setFloatUniform("depthEffect", 1f);
        lens.setFloatUniform("chromaticAberration", 0.3f);

        float dim = real != null && !appsBg ? 1f : (dimOverride > 0 ? dimOverride : watch.dim);
        ColorMatrix colors = new ColorMatrix();
        colors.setSaturation(1.5f);
        ColorMatrix dimmed = new ColorMatrix();
        dimmed.setScale(dim, dim, dim, 1f);
        colors.postConcat(dimmed);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(lens);
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(colors));

        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(left, top, left + w, top + h, radius, radius, android.graphics.Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(left, top, left + w, top + h, paint);
        canvas.drawColor(Color.argb(Math.round(255 * surfaceAlpha), 255, 255, 255));

        RuntimeShader edge = new RuntimeShader(highlight);
        edge.setFloatUniform("size", w, h);
        edge.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        // The rim is the backdrop's own colour, lightened, so it belongs to whatever is behind the folder.
        float[] pts = {left + w * 0.5f, top + h * 0.5f, left + w * 0.15f, top + h * 0.5f, left + w * 0.85f,
                top + h * 0.5f, left + w * 0.5f, top + h * 0.15f, left + w * 0.5f, top + h * 0.85f};
        toScreen.mapPoints(pts);
        float kk = (float) dm.widthPixels / bg.getWidth();
        int sr = 0, sg = 0, sb = 0;
        for (int i = 0; i < 5; i++) {
            int px = bg.getPixel(Math.max(0, Math.min(bg.getWidth() - 1, (int) (pts[2 * i] / kk))),
                    Math.max(0, Math.min(bg.getHeight() - 1, (int) (pts[2 * i + 1] / kk))));
            sr += Color.red(px);
            sg += Color.green(px);
            sb += Color.blue(px);
        }
        int tint = Color.rgb(Math.round((sr / 5f * dim + 255) / 2), Math.round((sg / 5f * dim + 255) / 2),
                Math.round((sb / 5f * dim + 255) / 2));
        edge.setColorUniform("color", tint);
        edge.setFloatUniform("angle", (float) Math.toRadians(45));
        edge.setFloatUniform("falloff", 2f);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setShader(edge);
        stroke.setAlpha(frosted ? 150 : 105);
        stroke.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        stroke.setStrokeWidth(1.7f * dm.density);
        canvas.translate(left, top);
        canvas.drawRoundRect(0, 0, w, h, radius, radius, stroke);
        {
            // A hairline all the way round, so the edge reads as a glass rim even where the highlight fades.
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.1f * dm.density);
            rim.setColor(Color.argb(45, Color.red(tint), Color.green(tint), Color.blue(tint)));
            canvas.drawRoundRect(0, 0, w, h, radius, radius, rim);
        }
        canvas.restore();
    }

    private void loadWallpaper(int sw, int sh) {
        if (!wallLoading.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                Drawable d = WallpaperManager.getInstance(context).getDrawable();
                if (d == null || d.getIntrinsicWidth() <= 0) return;
                int w = 240, h = Math.max(1, Math.round(w * (float) sh / sw));
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                // Center-crop like the launcher's wallpaper.
                float s = Math.max((float) w / d.getIntrinsicWidth(), (float) h / d.getIntrinsicHeight());
                int dw = Math.round(d.getIntrinsicWidth() * s), dh = Math.round(d.getIntrinsicHeight() * s);
                d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                d.draw(new Canvas(b));
                // Shrunk with filtering: soft enough to read as frosted, sharp enough to show the bending.
                Bitmap soft = b;
                wallSoft = blur(b.copy(Bitmap.Config.ARGB_8888, false), 6);
                wall = soft;
                // Views that were drawn before the wallpaper arrived get another pass with the lens.
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    for (View v : new java.util.ArrayList<>(watched.keySet())) v.invalidate();
                });
                Diag.log("폴더 렌즈: 배경화면 읽음 " + soft.getWidth() + "x" + soft.getHeight());
            } catch (Throwable t) {
                Diag.log("폴더 렌즈: 배경화면을 읽지 못함 " + t);
            } finally {
                wallLoading.set(false);
            }
        }, "AutoClassify-wall").start();
    }

    // ---- setting ------------------------------------------------------------------------------------

    /** The blur strength is read again at every opening (a call into the module, a few milliseconds). */
    private void refreshNow() {
        if (System.currentTimeMillis() - loadedAt < 1500) return;
        load();
    }

    private void refreshIfStale() {
        if (System.currentTimeMillis() - loadedAt < TTL_MS || !loading.compareAndSet(false, true)) return;
        new Thread(this::load, "AutoClassify-folderlook").start();
    }

    /** Reads the style from the module; the first read is made before any folder is drawn so it applies at once. */
    private void load() {
        try {
            Bundle b = context.getContentResolver().call(
                    Uri.parse("content://" + Config.CLASSIFIER_AUTHORITY), "app_overrides", null, null);
            if (b != null) {
                glass = "glass".equals(b.getString("folder_style"));
                int level = Math.max(0, Math.min(100, b.getInt("folder_blur", 30)));
                // Closed tiles bend the same blurred apps screen, so a new strength retakes it.
                if (level != blurLevel) appsShotAt = 0;
                blurLevel = level;
                Diag.log("폴더 배경 설정: " + b.getString("folder_style") + " 흐림 " + blurLevel);
            }
        } catch (Throwable ignored) {
        } finally {
            loadedAt = System.currentTimeMillis();
            loading.set(false);
        }
    }
}
