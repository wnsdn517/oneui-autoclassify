package com.auto.classify.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who made an app, as far as the phone can tell without the network:
 * the organisation in the signing certificate, unless it is one of the generic certificates
 * (Play App Signing keys and AOSP test keys all read "Android / Google Inc."), in which case the
 * package namespace is used (com.kakao.talk -> kakao).
 */
public final class Developers {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Developers() {
    }

    /** Never null; lower-case match keys are derived from it by {@link #matches}. */
    public static String of(PackageManager pm, String pkg) {
        String cached = CACHE.get(pkg);
        if (cached != null) return cached;
        String org = signerOrg(pm, pkg);
        String ns = namespace(pkg);
        String dev = org != null ? org : ns;
        CACHE.put(pkg, dev);
        return dev;
    }

    /** Google apps that do not live under com.google.* (their signer is generic, so the namespace is "android"). */
    private static final String[] GOOGLE_OUTSIDE_NAMESPACE = {
            "com.android.vending", "com.android.chrome", "com.chrome.", "com.android.googlequicksearchbox"
    };

    /** dev:X matches when X appears in the developer name or equals the package namespace. */
    public static boolean matches(String query, String developer, String pkg) {
        String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
        if (q.isEmpty()) return false;
        if (q.equals("google") && isGoogleOutsideNamespace(pkg)) return true;
        String d = developer == null ? "" : developer.toLowerCase(Locale.ROOT).replace(" ", "");
        return d.contains(q) || namespace(pkg).equalsIgnoreCase(q);
    }

    private static boolean isGoogleOutsideNamespace(String pkg) {
        for (String p : GOOGLE_OUTSIDE_NAMESPACE) if (pkg.startsWith(p)) return true;
        return false;
    }

    /** Second package segment for com/org/net/io/kr..., else the first. */
    public static String namespace(String pkg) {
        String[] parts = pkg.split("\\.");
        if (parts.length >= 2 && parts[0].length() <= 3) return parts[1];
        return parts[0];
    }

    private static String signerOrg(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null) return null;
            Signature[] sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            if (sigs == null || sigs.length == 0) return null;
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(sigs[sigs.length - 1].toByteArray()));
            String dn = cert.getSubjectX500Principal().getName();
            String o = field(dn, "O"), cn = field(dn, "CN");
            boolean generic = "Android".equalsIgnoreCase(cn) || "Android Debug".equalsIgnoreCase(cn)
                    || (o != null && o.toLowerCase(Locale.ROOT).startsWith("google") && !pkg.startsWith("com.google."));
            if (generic) return null;
            if (o != null && !o.isEmpty() && !"Unknown".equalsIgnoreCase(o)) return o;
            if (cn != null && !cn.isEmpty() && !"Unknown".equalsIgnoreCase(cn)) return cn;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String field(String dn, String key) {
        for (String part : dn.split("(?<!\\\\),")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1).replace("\\", "").replace("\"", "").trim();
        }
        return null;
    }
}
