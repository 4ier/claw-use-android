plugins {
    id("com.android.application")
}

android {
    namespace = "com.clawuse.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clawuse.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.6.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.12.0")
}
