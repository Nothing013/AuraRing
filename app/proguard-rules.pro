# Shizuku reaches its server through reflection / hidden APIs.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Keep the manifest-declared components (safety net for release builds).
-keep class app.aura.ring.service.** { *; }
-keep class app.aura.ring.tasker.** { *; }

# Enums are stored and restored by name (preferences, Tasker broadcasts).
-keepclassmembers enum app.aura.ring.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepnames class app.aura.ring.RingMode
-keepnames class app.aura.ring.NotifPattern

# Compose / coroutines
-dontwarn kotlinx.coroutines.**
