plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.vallequaranta.segnale"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.vallequaranta.segnale"
        // Android 10+: requestCellInfoUpdate, CellInfoNr, getAllCellInfo per SIM
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Mappa OpenStreetMap, nessuna chiave API necessaria
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
}
