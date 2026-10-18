# Media3 ships its own consumer rules. Nothing else needs keeping.
-dontwarn org.checkerframework.**
-dontwarn kotlin.**
-dontwarn com.google.errorprone.annotations.**
# Methods called from the page script.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
