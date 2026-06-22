import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

// Prova a cercare il file in due posti: root del progetto o cartella app
var keystorePropertiesFile = rootProject.file("keystore.properties")
if (!keystorePropertiesFile.exists()) {
    keystorePropertiesFile = project.file("keystore.properties")
}

val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    println("Keystore properties caricate da: ${keystorePropertiesFile.absolutePath}")
} else {
    println("ERRORE: File keystore.properties NON TROVATO!")
    println("Assicurati che esista in: ${rootProject.file("keystore.properties").absolutePath}")
    println("Oppure in: ${project.file("keystore.properties").absolutePath}")
}

android {
    namespace = "com.technicalwork.materiali"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.technicalwork.materiali"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val alias = keystoreProperties["keyAlias"] as? String
            val keyPass = keystoreProperties["keyPassword"] as? String
            val storePass = keystoreProperties["storePassword"] as? String
            val storeFileStr = keystoreProperties["storeFile"] as? String

            if (alias != null && keyPass != null && storePass != null && storeFileStr != null) {
                keyAlias = alias
                keyPassword = keyPass
                storePassword = storePass
                
                // Cerca il file .jks relativo alla posizione del file .properties
                val jksFile = keystorePropertiesFile.parentFile.resolve(storeFileStr)
                if (jksFile.exists()) {
                    storeFile = jksFile
                } else {
                    println("ERRORE: File JKS non trovato in: ${jksFile.absolutePath}")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        buildConfig = true
        viewBinding = false
        dataBinding = false
        aidl = false
        shaders = false
        resValues = false
    }

}

// Commented out to prevent compiler crash: PerformanceManager functions can be run only from the same thread
// tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
//     compilerOptions {
//         freeCompilerArgs.add("-Xbackend-threads=0") // Use all CPU cores for compilation
//     }
// }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.apache.poi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.androidx.documentfile)
    
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore.ktx)

    // GPS: FusedLocationProviderClient per posizione affidabile
    implementation("com.google.android.gms:play-services-location:21.3.0")
    
    // QR Code (Scambio Materiale)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // WorkManager — sync Firebase affidabile in background
    implementation(libs.androidx.work.runtime.ktx)
    
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
