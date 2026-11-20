# Shizuku reaches its server through reflection / hidden APIs.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Keep the manifest-declared components (safety net for release builds).
-keep class app.aura.ring.service.** { *; }
-keep class app.aura.ring.tasker.** { *; }

# Compose / coroutines
-dontwarn kotlinx.coroutines.**
