plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy

val bundledModelFileName = "gemma-2b-int4.gguf"
val bundledModelSource = rootProject.file("model-kit/$bundledModelFileName")
val bundledModelAssetsDir = layout.buildDirectory.dir("generated/bundled-model-assets/model")

val prepareBundledModel by tasks.registering(Copy::class) {
    group = "build setup"
    description = "Copies local bundled Gemma model into generated app assets."
    from(rootProject.file("model-kit")) {
        include(bundledModelFileName)
    }
    into(bundledModelAssetsDir)
}

val verifyBundledModelForPackaging by tasks.registering {
    group = "verification"
    description = "Fails packaging/install if the local bundled model is missing."
    doLast {
        if (!bundledModelSource.exists()) {
            throw GradleException(
                "Bundled model missing: ${bundledModelSource.absolutePath}. " +
                    "Place '$bundledModelFileName' in the root 'model-kit/' directory before assembling/installing APKs.",
            )
        }
    }
}

android {
    namespace = "com.orka.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orka.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-internal"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(bundledModelAssetsDir)
        }
    }

    androidResources {
        noCompress += "gguf"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        animationsDisabled = true
    }
}

tasks.matching {
    it.name in setOf(
        "mergeDebugAssets",
        "mergeReleaseAssets",
        "packageDebug",
        "packageRelease",
        "bundleDebug",
        "bundleRelease",
        "assembleDebug",
        "assembleRelease",
        "installDebug",
        "installRelease",
    )
}.configureEach {
    dependsOn(prepareBundledModel)
    dependsOn(verifyBundledModelForPackaging)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.database)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.capture)
    implementation(projects.feature.tasks)
    implementation(projects.feature.taskdetail)
    implementation(projects.feature.archive)
    implementation(projects.feature.settings)
    implementation(projects.feature.diagnostics)
    implementation(projects.feature.alarm)
    implementation(projects.data.parser)
    implementation(projects.data.scheduler)
    implementation(projects.data.behavior)
    implementation(projects.data.execution)
    implementation(projects.data.rl)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.navigation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)

    ksp(libs.hilt.compiler)

    testImplementation(projects.core.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
