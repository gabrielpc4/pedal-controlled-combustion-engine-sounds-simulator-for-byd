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
val prepareCarPreviewAssets = tasks.register<Sync>("prepareCarPreviewAssets") {
    listOf(
        rootProject.file("../original_cars"),
        rootProject.file("../new_cars"),
    ).filter(File::isDirectory).forEach { sourceRoot ->
        from(sourceRoot) {
            include("*/preview1.jpg")
            eachFile {
                val normalizedCarId = file.parentFile.name.lowercase().replace("_", "-")
                path = "car_previews/$normalizedCarId.jpg"
            }
            includeEmptyDirs = false
        }
    }
    val bankPreviewAliases = mapOf(
        "assetto-audi-r8-lms-2016" to rootProject.file("../new_cars/audi-r8-lms-gt2/preview1.jpg"),
        "assetto-audi-r8-plus" to rootProject.file("../new_cars/audi-r8-lms-gt2/preview1.jpg"),
        "assetto-audi-tt-cup" to rootProject.file("../new_cars/audi-tt-cup-2015/preview1.jpg"),
        "assetto-bmw-m4" to rootProject.file("../new_cars/bmw-m8-gtlm/preview1.jpg"),
        "assetto-corvette-c7-stingray" to rootProject.file("../new_cars/chevrolet-corvette-c7-stingray-hellspec/preview1.jpg"),
        "assetto-ferrari-458" to rootProject.file("../new_cars/ferrari-458-italia-tune/preview1.jpg"),
        "assetto-ferrari-458-gt2" to rootProject.file("../new_cars/ferrari-458-italia-tune/preview1.jpg"),
        "assetto-ferrari-488-gtb" to rootProject.file("../new_cars/ferrari-488-gte-evo-michelotto/preview1.jpg"),
        "assetto-ferrari-488-gt3" to rootProject.file("../new_cars/ferrari-488-gte-evo-michelotto/preview1.jpg"),
        "assetto-ferrari-fxx-k" to rootProject.file("../new_cars/ferrari-laferrari-trio/preview1.jpg"),
        "assetto-ferrari-laferrari" to rootProject.file("../new_cars/ferrari-laferrari-trio/preview1.jpg"),
        "assetto-lamborghini-aventador-sv" to rootProject.file("../original_cars/lamborghini-aventador-sv/preview1.jpg"),
        "assetto-lamborghini-gallardo-sl" to rootProject.file("../original_cars/lamborghini-huracan-trofeo-evo2/preview1.jpg"),
        "assetto-lamborghini-huracan-performante" to rootProject.file("../original_cars/lamborghini-huracan-trofeo-evo2/preview1.jpg"),
        "assetto-lamborghini-huracan-st" to rootProject.file("../original_cars/lamborghini-huracan-trofeo-evo2/preview1.jpg"),
        "assetto-mercedes-amg-gt3" to rootProject.file("../new_cars/mercedes-benz-amg-gt3-evo-2020-sprint/preview1.jpg"),
        "assetto-nissan-370z" to rootProject.file("../new_cars/nissan-370z-widebody/preview1.jpg"),
        "assetto-nissan-gtr" to rootProject.file("../new_cars/nissan-gt-r-nismo-godzilla/preview1.jpg"),
        "assetto-porsche-911-gt3-rs" to rootProject.file("../new_cars/porsche-911-gt3-rs-hellspec/preview1.jpg"),
        "assetto-porsche-991-turbo-s" to rootProject.file("../new_cars/porsche-911-turbo-s/preview1.jpg"),
        "assetto-toyota-supra-mkiv" to rootProject.file("../new_cars/toyota-supra-wangan/preview1.jpg"),
    )
    bankPreviewAliases.forEach { (assetName, source) ->
        from(source) {
            rename { "$assetName.jpg" }
            into("car_previews")
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
    sourceSets.getByName("main").assets.srcDir(generatedPreviewAssets)
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
