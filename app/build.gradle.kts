// TraceNFindTracker/app/build.gradle.kts (FINAL DEPENDENCY LIST)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // ✅ CRITICAL FIX: Add the Google Services plugin
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.tracenfindtracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tracenfindtracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Android Core & Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose BOM (manages versions)
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended") // ✅ Compose Icons
    implementation("androidx.compose.material:material-icons-extended:1.7.0") // or your compose version

    // ✅ NEW: Needed for the GoogleSignInClient (Google Sign-In UI)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // ✅ NEW: Needed for the HorizontalPager (feature tour)
    // Since you use the BOM, you don't need to specify a version.
    implementation("androidx.compose.foundation:foundation")

    // Google Services (Location)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ✅ ADD THIS (OpenStreetMap for Android)
    implementation("org.osmdroid:osmdroid-android:6.1.16")

    // 🔥 CRITICAL: Firebase Dependencies (Needed by LocationService and MainActivity)
    implementation(platform("com.google.firebase:firebase-bom:33.0.0")) // Bill of Materials
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // ✅ ADD THIS LINE:
    implementation("com.google.firebase:firebase-storage-ktx")

    // 📸 CAMERA X (FIX: Needed by CameraActivity.kt)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // 🌐 HTTP NETWORKING (FIX: Needed by CameraActivity.kt for photo upload)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.socket:socket.io-client:2.1.0")

    // Accompanist Pager (HorizontalPager + Indicator)
    implementation("com.google.accompanist:accompanist-pager:0.34.0")
    implementation("com.google.accompanist:accompanist-pager-indicators:0.34.0")

    // Tests (standard)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}