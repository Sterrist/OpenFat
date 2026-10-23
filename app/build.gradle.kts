plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.sterrist.openfat"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.sterrist.openfat"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    // Ключ подписи берётся из ~/.gradle/gradle.properties или переменных окружения, в репозиторий не попадает.
    val releaseKeystore = providers.gradleProperty("openfat.keystore").orElse(providers.environmentVariable("OPENFAT_KEYSTORE")).orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("openfat.keystore.password").orElse(providers.environmentVariable("OPENFAT_KEYSTORE_PASSWORD")).get()
                keyAlias = providers.gradleProperty("openfat.key.alias").orElse(providers.environmentVariable("OPENFAT_KEY_ALIAS")).get()
                keyPassword = providers.gradleProperty("openfat.key.password").orElse(providers.environmentVariable("OPENFAT_KEY_PASSWORD")).get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
