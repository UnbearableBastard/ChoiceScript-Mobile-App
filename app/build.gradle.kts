plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.csideandroid"

    // Use a stable SDK level (adjust if you specifically need 36 preview)
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.csideandroid"
        minSdk = 26
        targetSdk = 36

        versionCode = 8
        versionName = "2.7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Enable ViewBinding so we can reference views without findViewById
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Use Java 17 for toolchain + Kotlin JVM 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("io.github.rosemoe:editor:0.24.5")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation("junit:junit:4.13.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)        // activity-ktx via version catalog
    implementation(libs.androidx.constraintlayout)
    implementation("com.google.android.material:material:1.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
