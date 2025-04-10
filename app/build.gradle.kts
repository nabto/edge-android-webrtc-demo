plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

// We keep this here so it can be exposed to BuildConfig.
val nabtoWrapperVersion = "3.0.1"
val webrtcVersion = "main-SNAPSHOT"


android {
    compileSdk = 34
    namespace = "com.nabto.edge.webrtcdemo"

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        buildConfigField("String", "NABTO_WRAPPER_VERSION", "\"${nabtoWrapperVersion}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // consumerProguardFiles("consumer-rules.pro")

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Google play libraries
    implementation ("com.google.android.play:asset-delivery:2.2.2")
    implementation ("com.google.android.play:app-update:2.1.0")
    implementation ("com.google.android.gms:play-services-tasks:18.2.0")
    implementation ("com.google.android.play:feature-delivery:2.1.0")

    // Android dependencies
    implementation ("androidx.core:core-ktx:1.13.1")
    implementation ("com.google.android.material:material:1.12.0")
    implementation ("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation ("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation ("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation ("androidx.navigation:navigation-dynamic-features-fragment:2.7.7")
    implementation ("androidx.legacy:legacy-support-v4:1.0.0")
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation ("com.google.android.gms:play-services-vision:20.1.3")
    implementation ("androidx.preference:preference-ktx:1.2.1")

    // Nabto dependencies
    implementation ("com.nabto.edge.client:library:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:library-ktx:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:iam-util:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:iam-util-ktx:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:webrtc:$webrtcVersion")

    // Kotlin dependencies
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation ("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation ("androidx.lifecycle:lifecycle-process:2.8.4")

    // Room persistence library to use a database abstracted over sqlite
    val roomVersion = "2.6.1"
    implementation ("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation ("androidx.room:room-ktx:$roomVersion")

    // Koin dependency injection
    val koinVersion = "3.2.0"
    implementation ("io.insert-koin:koin-core:$koinVersion")
    implementation ("io.insert-koin:koin-android:$koinVersion")
    implementation ("io.insert-koin:koin-androidx-workmanager:$koinVersion")
    implementation ("io.insert-koin:koin-androidx-navigation:$koinVersion")
    testImplementation ("io.insert-koin:koin-test:$koinVersion")

    // Amplify for Cognito integration
    val amplifyVersion = "2.21.0"
    implementation ("com.amplifyframework:core-kotlin:$amplifyVersion")
    implementation ("com.amplifyframework:aws-auth-cognito:$amplifyVersion")

    // Retrofit for http requests to the smarthome service
    implementation ("com.squareup.okhttp3:okhttp:4.10.0")
    implementation ("com.squareup.retrofit2:retrofit:2.9.0")

    // GetStream WebRTC implementation
    val webrtcVersion = "1.0.5"
    implementation ("io.getstream:stream-webrtc-android:$webrtcVersion")
    implementation ("io.getstream:stream-webrtc-android-ui:$webrtcVersion")
    implementation ("io.getstream:stream-webrtc-android-ktx:$webrtcVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}