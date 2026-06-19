-keep class org.postgresql.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.postgresql.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# SourceAFIS — ISO/IEC 19794-2 fingerprint matching
-keep class com.machinezoo.sourceafis.** { *; }
-keep class com.machinezoo.noexception.** { *; }
-keep class com.machinezoo.stagezero.** { *; }
-dontwarn com.machinezoo.**
