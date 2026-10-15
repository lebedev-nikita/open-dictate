import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingKey = providers.environmentVariable("SIGNING_KEY")
val keyAliasValue = providers.environmentVariable("KEY_ALIAS")
val keyPasswordValue = providers.environmentVariable("KEY_PASSWORD")
val storePasswordValue = providers.environmentVariable("STORE_PASSWORD")
val releaseStore = layout.buildDirectory.file("keystore/opendictate-release.jks")
val appVersionName = providers.gradleProperty("appVersionName").orElse("0.13.0-rc.7")
val appVersionCode = providers.gradleProperty("appVersionCode").map(String::toInt).orElse(78)
val previewVersionSuffix = providers.gradleProperty("previewVersionSuffix").orElse("-preview")

if (signingKey.isPresent) {
    val output = releaseStore.get().asFile
    output.parentFile.mkdirs()
    output.writeBytes(Base64.getDecoder().decode(signingKey.get()))
}

android {
    namespace = "com.opendictate.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opendictate.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // Match locales_config.xml; omit unused dependency translations from the APK.
        resourceConfigurations += listOf("en", "ru")
    }

    signingConfigs {
        if (signingKey.isPresent) {
            create("release") {
                storeFile = releaseStore.get().asFile
                storePassword = storePasswordValue.orNull
                keyAlias = keyAliasValue.orNull
                keyPassword = keyPasswordValue.orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingKey.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = previewVersionSuffix.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
