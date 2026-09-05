# ==============================================================================
# Technicalwork Materiali - ProGuard / R8 Obfuscation Rules
# ==============================================================================

# Preserva le informazioni di riga nei log e crash stacktrace
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserva le annotazioni e i tipi generici necessari per reflection e coroutine
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# ------------------------------------------------------------------------------
# 1. Modelli Dati dell'Applicazione (Serializzazione Gson, Firestore & Cache)
# ------------------------------------------------------------------------------
# Mantiene inalterati i campi e costruttori delle data class usate per Firestore e JSON
-keep class com.technicalwork.materiali.ExchangeItem { *; }
-keep class com.technicalwork.materiali.ExchangeLog { *; }
-keep class com.technicalwork.materiali.ExcelRowData { *; }
-keep class com.technicalwork.materiali.PfsItem { *; }
-keep class com.technicalwork.materiali.QueuedPfsItem { *; }
-keep class com.technicalwork.materiali.QueuedData { *; }
-keep class com.technicalwork.materiali.Stock { *; }
-keep class com.technicalwork.materiali.AppConfig { *; }
-keep class com.technicalwork.materiali.GitHubRelease { *; }
-keep class com.technicalwork.materiali.GitHubAsset { *; }
-keep class com.technicalwork.materiali.UndoSnapshot { *; }

# Mantieni i membri delle classi interne e adapter data
-keepclassmembers class com.technicalwork.materiali.** {
    <fields>;
    public <init>(...);
}

# ------------------------------------------------------------------------------
# 2. Google Firebase & Firestore
# ------------------------------------------------------------------------------
-dontwarn com.google.firebase.**
-keep class com.google.firebase.** { *; }

# ------------------------------------------------------------------------------
# 3. Gson (Serializzazione JSON)
# ------------------------------------------------------------------------------
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers enum * { *; }

# ------------------------------------------------------------------------------
# 4. Apache POI & XMLBeans (Elaborazione file Excel)
# ------------------------------------------------------------------------------
-dontwarn java.awt.**
-dontwarn com.graphbuilder.**
-dontwarn org.apache.poi.**
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.xmlbeans.**
-keep class org.apache.xmlbeans.** { *; }
-dontwarn javax.xml.stream.**
-keep class javax.xml.stream.** { *; }

# ------------------------------------------------------------------------------
# 5. ZXing Barcode Scanner (Scambio Materiale QR)
# ------------------------------------------------------------------------------
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

# ------------------------------------------------------------------------------
# 6. AndroidX Startup, WorkManager & Room (Startup & Background Sync)
# ------------------------------------------------------------------------------
-keep class androidx.startup.** { *; }
-keep class * implements androidx.startup.Initializer { *; }
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.InputMerger { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
    public <init>(...);
}
-dontwarn androidx.work.impl.**
-dontwarn androidx.room.**

# ------------------------------------------------------------------------------
# 7. Google Play Services Location & AndroidX Lifecycle
# ------------------------------------------------------------------------------
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.location.**
-keep class androidx.lifecycle.ProcessLifecycleOwnerInitializer { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }

# ------------------------------------------------------------------------------
# 8. Kotlin Coroutines & OkHttp
# ------------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-dontwarn okio.**