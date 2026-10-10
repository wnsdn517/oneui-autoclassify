# The module is loaded by LSPosed and talks to One UI Home through reflection: keep it whole
# and only shrink the libraries.
-keep class com.auto.classify.** { *; }
-dontobfuscate
-dontwarn de.robv.android.xposed.**
