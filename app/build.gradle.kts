plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.lazymodification" // ⚠️ См. примечание ниже про пакет
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.lazymodification"
        minSdk = 21
        targetSdk = 35
        versionCode = 121
        versionName = "1.21"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    // В новых версиях AGP packagingOptions заменен на packaging
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ✅ Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // ✅ Material Design
    implementation("com.google.android.material:material:1.11.0")

    // ✅ ConstraintLayout & RecyclerView
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ✅ LocalBroadcastManager (для логов)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // ✅ ARSCLib (для работы с APK)
    implementation("io.github.reandroid:ARSCLib:1.4.0")

    // ✅ APK Signer (для подписи APK)
    implementation("com.android.tools.build:apksig:8.2.0")

    // ✅ DexLib2 (для патчинга DEX)
    implementation("org.smali:dexlib2:2.5.2")

    // ✅ Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ✅ Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // ✅ BouncyCastle (поддержка JKS keystore на Android)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78")
}