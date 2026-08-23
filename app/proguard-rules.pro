# --- SplitBill R8 / ProGuard --------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod

-keep class com.anant.splitbill.data.model.** { *; }
-keep class com.anant.splitbill.data.database.** { *; }
-keep class com.anant.splitbill.data.backup.** { *; }
-keep class com.anant.splitbill.**JsonAdapter { *; }

-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-dontwarn org.jetbrains.annotations.**

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn kotlinx.coroutines.**

-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class io.sentry.** { *; }
-dontwarn io.sentry.**
