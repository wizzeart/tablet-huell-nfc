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

# sherpa-onnx — TTS neuronal (JNI: la .so liga los métodos/campos Java por nombre,
# renombrarlos rompe el binding nativo en runtime)
-keep class com.k2fsa.sherpa.** { *; }
-dontwarn com.k2fsa.sherpa.**

# Gson — los modelos se mapean por nombre de campo vía reflexión.
# En PetronovaApiClient solo se serializan Map<String,Object> con claves literales (safe),
# pero las clases-modelo deben conservar sus nombres de campo intactos.
#   - PisteroData (anidada en LocalConfigStore) se serializa con gson.toJson/fromJson.
-keep class com.petronova.kiosk.data.model.** { *; }
-keep class com.petronova.kiosk.data.local.LocalConfigStore$PisteroData { *; }
-keepattributes Signature, *Annotation*
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**
