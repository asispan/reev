# sherpa-onnx is driven over JNI: native code looks up these classes, fields and methods by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Release builds carry no verbose, debug or info logging (dictated text must never reach logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
