plugins {
    alias(libs.plugins.android.application)
}

import org.gradle.api.tasks.Sync

val generatedPackAssets = file("build/generated/packAssets")
val preparePackAssets = tasks.register<Sync>("preparePackAssets") {
    from(rootProject.file("fmod_bank_packs")) {
        include("*.bydbank", "index.json")
        into("packs")
    }
    into(generatedPackAssets)
}

tasks.named("preBuild").configure {
    dependsOn(preparePackAssets)
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

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets.getByName("main").assets.srcDir(generatedPackAssets)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("engine-sounds-audio-installer-${variant.name}.apk")
        }
    }
}
