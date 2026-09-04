plugins {
    alias(libs.plugins.android.application)
}

import org.gradle.api.tasks.Sync

val generatedModdedPackAssets = file("build/generated/packAssets/modded")
val generatedOriginalPackAssets = file("build/generated/packAssets/original")
val prepareModdedPackAssets = tasks.register<Sync>("prepareModdedPackAssets") {
    from(rootProject.file("fmod_bank_packs")) {
        include("modded-*.bydbank", "assetto-common*.bydbank", "index.json")
        into("packs")
    }
    into(generatedModdedPackAssets)
}
val prepareOriginalPackAssets = tasks.register<Sync>("prepareOriginalPackAssets") {
    from(rootProject.file("fmod_bank_packs")) {
        include("assetto-*.bydbank", "alfa-romeo-4c.bydbank", "index.json")
        into("packs")
    }
    into(generatedOriginalPackAssets)
}

tasks.named("preBuild").configure {
    dependsOn(prepareModdedPackAssets, prepareOriginalPackAssets)
}

android {
    namespace = "com.gabrielpc.enginesoundsinstaller"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gabrielpc.enginesoundsinstaller"
        minSdk = 25
        targetSdk = 25
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "payload"
    productFlavors {
        create("modded") {
            dimension = "payload"
            applicationIdSuffix = ".modded"
            buildConfigField("String", "PAYLOAD_GROUP", "\"modded_car_packs\"")
        }
        create("original") {
            dimension = "payload"
            applicationIdSuffix = ".original"
            buildConfigField("String", "PAYLOAD_GROUP", "\"original_cars_pack\"")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    lint {
        // These installers target the same BYD DiLink Android compatibility level as the
        // sideloaded simulator APK and are not distributed through Google Play.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets.getByName("modded").assets.srcDir(generatedModdedPackAssets)
    sourceSets.getByName("original").assets.srcDir(generatedOriginalPackAssets)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("engine-sounds-audio-installer-${variant.name}.apk")
        }
    }
}
