# ShizukuCommandExecutor reaches Shizuku.newProcess through reflection because
# it is package-private in the SDK. R8 cannot see that call, so without these
# keeps it would strip the method and every privileged command would fail with
# a misleading "Shizuku unavailable" error.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# kotlinx.serialization resolves serializers for @Serializable classes
# reflectively through the companion object.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tauru.astrbotphoneagent.**$$serializer { *; }
-keepclassmembers class com.tauru.astrbotphoneagent.** {
    *** Companion;
}
-keepclasseswithmembers class com.tauru.astrbotphoneagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional platform integrations that are absent on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Components that only the manifest names are unreachable from code, so R8 can
# strip them while the merged manifest still points at them. Android then fails
# to instantiate the component at runtime. Verified by inspecting the release
# dex: without these rules NotificationRelayService is absent and the manifest
# reference dangles.
-keep class com.tauru.astrbotphoneagent.MainActivity { *; }
-keep class com.tauru.astrbotphoneagent.PhoneAgentApp { *; }
-keep class com.tauru.astrbotphoneagent.PhoneConnectionService { *; }
-keep class com.tauru.astrbotphoneagent.logic.NotificationRelayService { *; }
