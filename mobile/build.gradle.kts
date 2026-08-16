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

val storedBuildNumber = buildNumberProperties.getProperty("buildNumber", "1").toInt()
val stampedBuildNumber = if (isAssembling) storedBuildNumber + 1 else storedBuildNumber

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
)

val localEngineProfiles = listOf(
    LocalEngineProfileAssets(
        assetDirectory = "lamborghini_huracan_trofeo_evo2_exterior",
        sourceDirectory = rootProject.file("audio_samples/fx_lamborghini_huracan_trofeo_evo2/converted_exterior"),
        assetNames = listOf(
            "s003_front_amb_c1.wav", "s004_rear_high_l2.wav", "s009_ex_l3.wav",
            "s013_ex_idle.wav", "s016_ex_c2.wav", "s023_rear_l4.wav",
            "s025_rear_l1.wav", "s028_front_c2.wav", "s029_front_l4.wav",
            "s035_front_amb_l1.wav", "s040_front_c1.wav", "s042_ex_l6.wav",
            "s043_pass_mid.wav", "s048_front_c3.wav", "s056_front_c1_stro.wav",
            "s058_ex_c1e.wav", "s060_high_pressure_noise.wav", "s063_rear_c3.wav",
            "s069_loud_l1.wav", "s070_ex_h3.wav", "s079_front_amb_l2.wav",
            "s083_ex_c6.wav", "s084_rear_l5.wav", "s086_rear_limiter.wav",
            "s088_ex_l1b.wav", "s095_side_l1.wav", "s099_pass_high.wav",
            "s100_rear_high_l1.wav", "s104_side_l2.wav", "s107_loud_l3.wav",
            "s109_ex_h1.wav", "s111_front_l3.wav", "s114_ex_limiter.wav",
            "s115_front_l1.wav", "s122_ex_l2b_distance.wav", "s123_ex_l4.wav",
            "s124_front_c4.wav", "s125_loud_l2.wav", "s128_rear_c1.wav",
            "s131_ex_l5.wav", "s132_front_l2.wav", "s136_ex_l2a_far.wav",
            "s138_ex_c4.wav", "s144_front_amb_l3.wav", "s145_ex_c3.wav",
            "s147_front_amb_c2.wav", "s153_front_amb_c4.wav",
        ),
    ),
)
val generatedSampleEngineAssets = file("build/generated/sampleEngineAssets")
val prepareSampleEngineAssets = tasks.register<Sync>("prepareSampleEngineAssets") {
    localEngineProfiles.forEach { profile ->
        from(profile.sourceDirectory) {
            include(profile.assetNames)
            into("sample_engine/${profile.assetDirectory}")
        }
    }
    into(generatedSampleEngineAssets)
}

if (isAssembling) {
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
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
