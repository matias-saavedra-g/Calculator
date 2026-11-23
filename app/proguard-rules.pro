# R8 rules for the release build.
# Chaquopy, kotlinx.serialization and Compose ship their own consumer rules, and the
# EngineService is kept through the manifest, so nothing extra is required today.
# Keep serializable model classes' companions just in case of reflection lookups.
-keepclassmembers @kotlinx.serialization.Serializable class com.mattia.calculator.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
