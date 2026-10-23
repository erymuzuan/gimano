# Gimano release (R8). Most libraries ship consumer rules (Retrofit, OkHttp, Room, WorkManager,
# Coil, Play services, kotlinx.serialization core); the rules below cover reflection/JNI the app relies on.

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization: keep generated serializers of our @Serializable models ----
-keepclassmembers @kotlinx.serialization.Serializable class my.gimano.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class my.gimano.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class my.gimano.app.**$$serializer { *; }
-if @kotlinx.serialization.Serializable class my.gimano.app.**
-keepclassmembers class <1>$Companion { kotlinx.serialization.KSerializer serializer(...); }

# ---- Retrofit service interface (suspend functions + generic return types) ----
-keep,allowobfuscation interface my.gimano.app.data.remote.GimanoApi
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ---- SignalR (Gson + RxJava + reflection on hub message types) ----
-keep class com.microsoft.signalr.** { *; }
-dontwarn com.microsoft.signalr.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class io.reactivex.rxjava3.** { *; }
-dontwarn io.reactivex.rxjava3.**
-dontwarn org.slf4j.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.msgpack.**

# ---- MapLibre (JNI calls back into Java by name) ----
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# ---- lazysodium + JNA (JNA maps native functions onto interface methods by reflection) ----
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ---- Room: entities/DAOs are generated; keep entity field names used in raw SQL ----
-keep class my.gimano.app.data.local.** { *; }

# ---- WorkManager / BroadcastReceivers are referenced from the manifest (kept by AAPT rules) ----
-keep class my.gimano.app.data.backup.BackupWorker { <init>(...); }
