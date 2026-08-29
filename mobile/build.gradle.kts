plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.File
import java.time.Instant
import java.util.Properties
import org.gradle.api.tasks.Sync

val buildNumberFile = file("build-number.properties")
val buildNumberProperties = Properties()
if (buildNumberFile.exists()) {
    buildNumberFile.inputStream().use { buildNumberProperties.load(it) }
}

val isAssembling = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("assemble", ignoreCase = true)
}
val stampCarBuild = gradle.startParameter.projectProperties["carApk"] == "true"

val storedBuildNumber = buildNumberProperties.getProperty("buildNumber", "1").toInt()
val stampedBuildNumber = if (isAssembling && stampCarBuild) storedBuildNumber + 1 else storedBuildNumber
val coastLayerMixEnabledByDefault =
    (project.findProperty("coastLayerMixEnabledByDefault") as String?)?.toBooleanStrictOrNull()
        ?: (project.findProperty("coastOnlyFullGainExperiment") as String?)?.toBooleanStrictOrNull()
        ?: true

fun gitShortShaFromFiles(rootDir: File): String {
    val gitHead = File(rootDir, ".git/HEAD")
    if (!gitHead.exists()) {
        return "unknown"
    }

    val headContent = gitHead.readText().trim()
    if (!headContent.startsWith("ref:")) {
        return headContent.take(7)
    }

    val refPath = headContent.removePrefix("ref:").trim()
    val refFile = File(rootDir, ".git/$refPath")
    if (!refFile.exists()) {
        return "unknown"
    }

    return refFile.readText().trim().take(7)
}

val gitSha = gitShortShaFromFiles(rootProject.projectDir)
val buildTimeUtc: String = Instant.now().toString()

data class LocalEngineProfileAssets(
    val assetDirectory: String,
    val sourceDirectory: File,
    val assetNames: List<String>,
    val previewSource: File,
    val previewAssetName: String,
)

val localEngineProfiles = listOf(
    LocalEngineProfileAssets(
        assetDirectory = "lamborghini_huracan_trofeo_evo2",
        sourceDirectory = rootProject.file("audio_samples/fx_lamborghini_huracan_trofeo_evo2/converted"),
        assetNames = listOf(
            "s010_hur_n1_high.wav", "s031_hur_high_l1.wav", "s032_hur_l2a.wav",
            "s037_hur_idle_noise.wav", "s038_hur_high_l3.wav", "s039_hur_c1.wav",
            "s044_hur_l3.wav", "s049_eng_noise9_high.wav", "s059_hur_c2.wav",
            "s061_hur_n_up.wav", "s065_hur_l5.wav", "s073_hur_lim.wav",
            "s077_eng_noise7.wav", "s078_hur_idle_low.wav", "s081_hur_high_l2a.wav",
            "s089_hur_n2.wav", "s093_hur_c4.wav", "s113_hur_l1.wav",
            "s117_hur_l4h.wav", "s126_amrgt3_sine.wav", "s127_hur_l4.wav",
            "s134_hur_c3.wav", "s139_hur_l6.wav", "s149_hur_l4l.wav",
            "fx_transmission.wav", "fx_shift_up.wav", "fx_shift_down.wav",
        ),
        previewSource = rootProject.file("audio_samples/fx_lamborghini_huracan_trofeo_evo2/preview1.jpg"),
        previewAssetName = "lamborghini_huracan_trofeo_evo2.jpg",
    ),
    LocalEngineProfileAssets(
        assetDirectory = "lamborghini_aventador_sv",
        sourceDirectory = rootProject.file("audio_samples/tr_lamborghini_aventador_sv/converted"),
        assetNames = listOf("s006.wav", "s013.wav", "s039.wav", "s046.wav", "s048.wav", "s062.wav", "s063.wav", "s082.wav", "s098.wav", "s117.wav", "s118.wav", "s119.wav", "s127.wav", "s133.wav", "s138.wav", "s147.wav", "fx_transmission.wav", "fx_shift.wav"),
        previewSource = rootProject.file("audio_samples/tr_lamborghini_aventador_sv/preview1.jpg"),
        previewAssetName = "lamborghini_aventador_sv.jpg",
    ),
)
val generatedSampleEngineAssets = file("build/generated/sampleEngineAssets")
val prepareSampleEngineAssets = tasks.register<Sync>("prepareSampleEngineAssets") {
    localEngineProfiles.forEach { profile ->
        from(profile.sourceDirectory) {
            include(profile.assetNames)
            into("sample_engine/${profile.assetDirectory}")
        }
        from(profile.previewSource) {
            rename { profile.previewAssetName }
            into("car_previews")
        }
    }
    // The Huracán bank supplies this explicitly named exterior idle loop separately from the
    // reconstructed interior event. It replaces only the idle layer; driving layers remain
    // the recovered cabin program.
    from(rootProject.file("audio_samples/fx_lamborghini_huracan_trofeo_evo2/converted_exterior")) {
        include("s013_ex_idle.wav")
        into("sample_engine/lamborghini_huracan_trofeo_evo2")
    }
    from(rootProject.file("reference/alfa_romeo_4c_exhaust_effects/01_backfire_internal")) {
        include(
            "backfire_1.wav",
            "backfire_2.wav",
            "backfire_3.wav",
            "backfire_4.wav",
        )
        into("sample_engine/shared/pops_and_bangs")
    }
    into(generatedSampleEngineAssets)
}

if (isAssembling && stampCarBuild) {
    val nextBuildNumber = stampedBuildNumber
    val targetBuildNumberFile = buildNumberFile
    tasks.register("persistBuildNumber") {
        doLast {
            val props = Properties()
            props.setProperty("buildNumber", nextBuildNumber.toString())
            targetBuildNumberFile.outputStream().use { output ->
                props.store(output, "Auto-incremented on assemble")
            }
        }
    }
    tasks.named("preBuild").configure {
        dependsOn("persistBuildNumber")
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareSampleEngineAssets)
}

android {
    namespace = "com.gabrielpc.enginesoundsimulator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gabrielpc.enginesoundsimulator"
        // The BYD DiLink head unit is a vendor Android build rather than standard AAOS.
        // Target 25 matches known working BYD apps and avoids modern hidden-API blocking
        // while this read-only compatibility probe uses the vendor boot-classpath API.
        minSdk = 25
        targetSdk = 25
        versionCode = stampedBuildNumber
        versionName = "1.0.$stampedBuildNumber"

        buildConfigField("int", "BUILD_NUMBER", stampedBuildNumber.toString())
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_TIME_UTC", "\"$buildTimeUtc\"")
        buildConfigField("boolean", "COAST_LAYER_MIX_ENABLED_BY_DEFAULT", coastLayerMixEnabledByDefault.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(generatedSampleEngineAssets)
    lint {
        // This APK is intentionally sideloaded on a BYD DiLink head unit. Target 25 is a
        // compatibility requirement for its vendor framework, not a Google Play configuration.
        disable += "ExpiredTargetSdkVersion"
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "engine-sounds-simulator-build-$stampedBuildNumber-${variant.name}.apk",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
