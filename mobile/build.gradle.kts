plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.File
import java.time.Instant
import java.util.Properties
import org.gradle.api.tasks.Exec
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

val generatedPreviewAssets = file("build/generated/carPreviewAssets")
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val fmodSdkDirectory = file(
    providers.gradleProperty("fmod.sdk.dir").orNull
        ?: localProperties.getProperty("fmod.sdk.dir")
        ?: throw GradleException("Set fmod.sdk.dir to the local FMOD Android SDK directory."),
)
val generatedFmodSdk = file("build/generated/fmodSdk")

val prepareFmodSdk = tasks.register<Sync>("prepareFmodSdk") {
    require(fmodSdkDirectory.isDirectory) { "FMOD Android SDK directory does not exist: $fmodSdkDirectory" }
    from(File(fmodSdkDirectory, "api/core/inc")) {
        include("*.h", "*.hpp")
        into("include")
    }
    from(File(fmodSdkDirectory, "api/studio/inc")) {
        include("*.h", "*.hpp")
        into("include")
    }
    from(File(fmodSdkDirectory, "api/core/lib")) {
        include("**/libfmod.so")
        into("lib")
    }
    from(File(fmodSdkDirectory, "api/studio/lib")) {
        include("**/libfmodstudio.so")
        into("lib")
    }
    into(generatedFmodSdk)
}
val prepareCarPreviewAssets = tasks.register<Sync>("prepareCarPreviewAssets") {
    val originalCarsRoot = rootProject.file("../assetto_corsa_installation/content/cars")
    val originalSources = mapOf(
        "alfa-romeo-4c" to "ks_alfa_romeo_4c",
        "assetto-audi-r8-lms-2016" to "ks_audi_r8_lms_2016",
        "assetto-audi-r8-plus" to "ks_audi_r8_plus",
        "assetto-audi-tt-cup" to "ks_audi_tt_cup",
        "assetto-bmw-m4" to "ks_bmw_m4",
        "assetto-corvette-c7-stingray" to "ks_corvette_c7_stingray",
        "assetto-ferrari-458" to "ferrari_458",
        "assetto-ferrari-458-gt2" to "ferrari_458_gt2",
        "assetto-ferrari-488-gtb" to "ks_ferrari_488_gtb",
        "assetto-ferrari-488-gt3" to "ks_ferrari_488_gt3",
        "assetto-ferrari-fxx-k" to "ks_ferrari_fxx_k",
        "assetto-ferrari-laferrari" to "ferrari_laferrari",
        "assetto-lamborghini-aventador-sv" to "ks_lamborghini_aventador_sv",
        "assetto-lamborghini-gallardo-sl" to "ks_lamborghini_gallardo_sl",
        "assetto-lamborghini-huracan-performante" to "ks_lamborghini_huracan_performante",
        "assetto-lamborghini-huracan-st" to "ks_lamborghini_huracan_st",
        "assetto-mercedes-amg-gt3" to "ks_mercedes_amg_gt3",
        "assetto-nissan-370z" to "ks_nissan_370z",
        "assetto-nissan-gtr" to "ks_nissan_gtr",
        "assetto-porsche-911-gt3-rs" to "ks_porsche_911_gt3_rs",
        "assetto-porsche-991-turbo-s" to "ks_porsche_991_turbo_s",
        "assetto-toyota-supra-mkiv" to "ks_toyota_supra_mkiv",
    )
    originalSources.forEach { (assetName, sourceId) ->
        val directory = originalCarsRoot.resolve(sourceId)
        val preview = directory.resolve("ui/dlc_preview.png").takeIf { it.isFile }
            ?: directory.resolve("skins").walkTopDown()
                .filter { it.isFile && (it.name == "preview.jpg" || it.name == "preview.png") }
                .sortedBy { it.path }
                .firstOrNull()
        preview?.let { source ->
            from(source) {
                rename { "$assetName.jpg" }
                into("car_previews")
            }
        }
    }
    into(generatedPreviewAssets)
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
    dependsOn(prepareCarPreviewAssets)
    dependsOn(prepareFmodSdk)
}

android {
    namespace = "com.gabrielpc.enginesoundsimulator"
    // Keep the native bridge on the verified NDK used by the FMOD 2.03.14 runtime build.
    ndkVersion = "27.1.12297006"
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
    sourceSets.getByName("main").assets.srcDir(generatedPreviewAssets)
    sourceSets.getByName("main").jniLibs.srcDir(File(generatedFmodSdk, "lib"))
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    lint {
        // This APK is intentionally sideloaded on a BYD DiLink head unit. Target 25 is a
        // compatibility requirement for its vendor framework, not a Google Play configuration.
        disable += "ExpiredTargetSdkVersion"
    }
}

tasks.configureEach {
    if (name.contains("externalNativeBuild", ignoreCase = true)) {
        dependsOn(prepareFmodSdk)
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
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(files(File(fmodSdkDirectory, "api/core/lib/fmod.jar")))
}
