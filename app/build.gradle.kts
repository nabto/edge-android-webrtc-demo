plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

// We keep this here so it can be exposed to BuildConfig.
val nabtoWrapperVersion = "webrtc-SNAPSHOT"

android {
    compileSdk = 33
    namespace = "com.nabto.edge.webrtcdemo"

    defaultConfig {
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        buildConfigField("String", "NABTO_WRAPPER_VERSION", "\"${nabtoWrapperVersion}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // consumerProguardFiles("consumer-rules.pro")
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

    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
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
    // Android dependencies
    implementation ("androidx.core:core-ktx:1.9.0")
    implementation ("androidx.appcompat:appcompat:1.5.1")
    implementation ("com.google.android.material:material:1.7.0")
    implementation ("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation ("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation ("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation ("androidx.navigation:navigation-dynamic-features-fragment:2.5.3")
    implementation ("androidx.legacy:legacy-support-v4:1.0.0")
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")
    implementation ("com.google.android.gms:play-services-vision:20.1.3")
    implementation ("androidx.preference:preference-ktx:1.2.0")

    // Nabto dependencies
    implementation ("com.nabto.edge.client:library:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:library-ktx:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:iam-util:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:iam-util-ktx:$nabtoWrapperVersion")
    implementation ("com.nabto.edge.client:webrtc:$nabtoWrapperVersion")

    // Kotlin dependencies
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation ("androidx.annotation:annotation:1.4.0")
    implementation ("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    implementation ("androidx.lifecycle:lifecycle-process:2.5.1")

    // Room persistence library to use a database abstracted over sqlite
    val roomVersion = "2.4.2"
    implementation ("androidx.room:room-runtime:$roomVersion")
    kapt ("androidx.room:room-compiler:$roomVersion")
    implementation ("androidx.room:room-ktx:$roomVersion")

    // Koin dependency injection
    val koinVersion = "3.2.0"
    implementation ("io.insert-koin:koin-core:$koinVersion")
    implementation ("io.insert-koin:koin-android:$koinVersion")
    implementation ("io.insert-koin:koin-androidx-workmanager:$koinVersion")
    implementation ("io.insert-koin:koin-androidx-navigation:$koinVersion")
    testImplementation ("io.insert-koin:koin-test:$koinVersion")

    // Amplify for Cognito integration
    val amplifyVersion = "2.13.2"
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
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}