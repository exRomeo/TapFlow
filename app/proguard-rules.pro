# OpenCV's Java wrappers are called from native code by name.
-keep class org.opencv.** { *; }

# Strip debug and verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
