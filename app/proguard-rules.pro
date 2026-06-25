# R8 / ProGuard rules for JMAPJolt release builds.

# --- JMAP client (rs.ltt.jmap) ---
# Models are (de)serialized with Gson reflection; keep them and their members.
-keep class rs.ltt.jmap.** { *; }
-keepclassmembers class rs.ltt.jmap.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# --- Gson (pulled in by jmap-client) ---
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# --- OkHttp / Okio (transport for jmap-client) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Compile-only / optional deps referenced by jmap-client but absent at runtime ---
-dontwarn lombok.**
-dontwarn org.joda.convert.**
-dontwarn org.slf4j.impl.**

# --- SQLCipher (native bindings, reflection) ---
-keep class net.zetetic.** { *; }
-keep class net.sqlcipher.** { *; }

# --- UnifiedPush connector ---
-keep class org.unifiedpush.** { *; }

# --- WorkManager workers (instantiated by reflection) ---
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- App services/receivers/widgets referenced from the manifest are kept by
#     AGP automatically; nothing extra needed here. ---
