plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sysadmin.lasstore.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sysadmin.lasstore.wear"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.2.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.wear.tiles:tiles:1.5.0")
    implementation("androidx.wear.protolayout:protolayout:1.3.0")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("com.google.protobuf:protobuf-javalite:4.28.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}

dependencyLocking {
    lockAllConfigurations()
}
