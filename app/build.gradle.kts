plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hershaleh.videostop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hershaleh.videostop"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // מפתח חתימה קבוע: כל גרסה נחתמת באותו מפתח, ולכן אפשר לעדכן מעל הגרסה הקודמת.
    // המפתח נמצא בריפו (signing/videostop.jks) בכוונה, כדי שכל בנייה תהיה חתומה זהה.
    signingConfigs {
        create("videostop") {
            storeFile = rootProject.file("signing/videostop.jks")
            storePassword = "0LEpCRPEC9YYWk0jnhwMCMXL"
            keyAlias = "videostop"
            keyPassword = "0LEpCRPEC9YYWk0jnhwMCMXL"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("videostop")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("videostop")
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
