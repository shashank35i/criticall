plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.simats.criticall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.simats.criticall"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"AIzaSyBYcH5j0ce1Da2RaPv2iuisi3d4TY_XHqA\"")
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation("com.android.billingclient:billing-ktx:7.0.0")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.play.services.location)
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.lifecycle.runtime.ktx)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.alphacephei:vosk-android:0.3.47")

    implementation("com.razorpay:checkout:1.6.40")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.airbnb.android:lottie:6.5.2")
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    implementation("com.google.firebase:firebase-database:20.3.0")

}
