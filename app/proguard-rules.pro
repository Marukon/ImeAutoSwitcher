# LibXposed API 102 ProGuard Rules
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Keep Hook Entry and Hooker classes
-keep class io.github.marukon.imeauto.hook.** { *; }
-keepclassmembers class * implements io.github.libxposed.api.XposedInterface$Hooker {
    public static *** before(...);
    public static *** after(...);
}

# Keep Data Models and JSON Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class io.github.marukon.imeauto.data.** { *; }
-keepclassmembers class io.github.marukon.imeauto.data.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    public static final *** Companion;
}
-dontwarn kotlinx.serialization.**

# Compose Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
