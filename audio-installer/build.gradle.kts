plugins {
    alias(libs.plugins.android.application)
}

import org.gradle.api.tasks.Sync

val generatedModdedPackAssets = file("build/generated/packAssets/modded")
val generatedOriginalPackAssets = file("build/generated/packAssets/original")
val generatedProbePackAssets = file("build/generated/packAssets/probe")
val generatedAlfaPackAssets = file("build/generated/packAssets/alfa")
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
val prepareProbePackAssets = tasks.register<Sync>("prepareProbePackAssets") {
    from(rootProject.file("fmod_bank_packs")) { include("probe-test.txt", "probe-index.json") }
    into("packs")
    into(generatedProbePackAssets)
}
val prepareAlfaPackAssets = tasks.register<Sync>("prepareAlfaPackAssets") {
    from(rootProject.file("fmod_bank_packs")) {
        include("alfa-romeo-4c.bydbank", "assetto-common.bydbank", "assetto-common-strings.bydbank")
        into("packs")
    }
    into(generatedAlfaPackAssets)
}

tasks.named("preBuild").configure { dependsOn(prepareModdedPackAssets, prepareOriginalPackAssets, prepareProbePackAssets, prepareAlfaPackAssets) }


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
        buildConfigField("boolean", "ALFA_ONLY", "false")
    }

    buildFeatures { buildConfig = true }

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
        create("probe") {
            dimension = "payload"
            applicationIdSuffix = ".probe"
            buildConfigField("String", "PAYLOAD_GROUP", "\"probe_payload\"")
        }
        create("alfa") {
            dimension = "payload"
            applicationIdSuffix = ".alfa"
            buildConfigField("String", "PAYLOAD_GROUP", "\"original_cars_pack\"")
            buildConfigField("boolean", "ALFA_ONLY", "true")
        }
    }

    signingConfigs {
        // The BYD sideload path rejects unsigned APKs. Keep the same stable local certificate
        // convention as the dashboard so reinstalling this helper does not require a manual
        // uninstall first.
        getByName("debug") { enableV2Signing = true }
    }


    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = false
            }
        }
    }

    sourceSets.getByName("modded").assets.srcDir(generatedModdedPackAssets)
    sourceSets.getByName("original").assets.srcDir(generatedOriginalPackAssets)
    sourceSets.getByName("probe").assets.srcDir(generatedProbePackAssets)
    sourceSets.getByName("alfa").assets.srcDir(generatedAlfaPackAssets)

    lint {
        // These installers target the same BYD DiLink Android compatibility level as the
        // sideloaded simulator APK and are not distributed through Google Play.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("engine-sounds-audio-installer-${variant.name}.apk")
        }
    }
}
