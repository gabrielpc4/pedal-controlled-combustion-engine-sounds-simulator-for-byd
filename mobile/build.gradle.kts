plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction

abstract class ValidateFmodInputsTask : DefaultTask() {
    @get:Input
    abstract val fmodSdkPath: Property<String>

    @get:Input
    abstract val skylinePreviewPath: Property<String>

    @get:Input
    abstract val skylineStringsBankPath: Property<String>

    @get:Input
    abstract val skylineCommonBankPath: Property<String>

    @get:Input
    abstract val skylineBankPath: Property<String>

    @get:Input
    abstract val huracanBankPath: Property<String>

    @get:Input
    abstract val huracanPreviewPath: Property<String>

    @get:Input
    abstract val aventadorBankPath: Property<String>

    @get:Input
    abstract val aventadorPreviewPath: Property<String>

    @get:Input
    abstract val alfaBankPath: Property<String>

    @get:Input
    abstract val alfaPreviewPath: Property<String>

    @get:Input
    abstract val supraBankPath: Property<String>

    @get:Input
    abstract val supraPreviewPath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun validateInputs() {
        val fmodPath = fmodSdkPath.get()
        check(fmodPath.isNotBlank() && File(fmodPath).isDirectory) {
            "FMOD Studio API 1.10.11 Android SDK is not configured. " +
                "Set fmod.sdk.dir=<official extracted SDK directory> in local.properties."
        }
        val fmodDirectory = File(fmodPath)
        fun requireFile(file: File, label: String) {
            val sourceHint = when {
                file.toPath().startsWith(fmodDirectory.toPath()) ->
                    "Check fmod.sdk.dir in local.properties and reinstall the official " +
                        "FMOD Studio API 1.10.11 Android SDK if that file is absent."
                else ->
                    "Restore this private source under the repository's ignored audio_banks " +
                        "directory; banks and preview artwork are intentionally not tracked."
            }
            check(file.isFile) {
                "$label was not found at ${file.absolutePath}. $sourceHint"
            }
        }

        val header = fmodDirectory.resolve("api/lowlevel/inc/fmod_common.h")
        requireFile(fmodDirectory.resolve("api/lowlevel/lib/fmod.jar"), "FMOD Android Java wrapper")
        requireFile(header, "FMOD core header")
        requireFile(fmodDirectory.resolve("api/studio/inc/fmod_studio.hpp"), "FMOD Studio header")
        listOf("armeabi-v7a", "arm64-v8a", "x86_64").forEach { abi ->
            requireFile(fmodDirectory.resolve("api/lowlevel/lib/$abi/libfmod.so"), "FMOD core library for $abi")
            requireFile(fmodDirectory.resolve("api/studio/lib/$abi/libfmodstudio.so"), "FMOD Studio library for $abi")
        }
        check(Regex("#define\\s+FMOD_VERSION\\s+0x00011011\\b").containsMatchIn(header.readText())) {
            "fmod.sdk.dir must point to the FMOD Studio API 1.10.11 Android SDK; " +
                "${header.absolutePath} declares a different FMOD_VERSION."
        }

        val banks = listOf(
            Triple(
                File(skylineStringsBankPath.get()),
                "common.strings.bank",
                "f9b633795f1c1634f1f1f7e9fed8a5c53c9c6b46554cc52b7e7880d8b3481381",
            ),
            Triple(
                File(skylineCommonBankPath.get()),
                "common.bank",
                "821df0944062f5bf134b184daf099ab68fcdb549d06be1c13e721bfbfc5a6b3e",
            ),
            Triple(
                File(skylineBankPath.get()),
                "ks_nissan_skyline_r34.bank",
                "a50ba96017868f37c50804350ea7a159b1f13ef347af95aca28dd1b8743bbc93",
            ),
            Triple(
                File(huracanBankPath.get()),
                "fx_lamborghini_huracan_trofeo_evo2.bank",
                "74f5053dfcae0529027b37da993ece36d2ff3d26102af8370bfe6589d8f2479c",
            ),
            Triple(
                File(aventadorBankPath.get()),
                "tr_lamborghini_aventador_sv.bank",
                "b83116900c41666fedf7b7256793d3d8808930a40ab938f1b089efd13bf63e42",
            ),
            Triple(
                File(alfaBankPath.get()),
                "ks_alfa_romeo_4c.bank",
                "3e2c5d4341afda3131aa6095cdbacc46aa76592fca3b365cae00ae4fe6e3bf76",
            ),
            Triple(
                File(supraBankPath.get()),
                "zesty_toyota_supra_mk4_shuto_street.bank",
                "64cfba3e153903430d95ec339b81930085708a1f5a74145b01c46d93aa067c0d",
            ),
        )
        banks.forEach { (file, label, expectedHash) ->
            requireFile(file, "Assetto Corsa $label")
            val actualHash = file.sha256()
            check(actualHash == expectedHash) {
                "$label has SHA-256 $actualHash, expected $expectedHash. " +
                    "The build will not package an unknown or modified bank."
            }
        }
        requireFile(File(skylinePreviewPath.get()), "Skyline preview")
        requireFile(File(huracanPreviewPath.get()), "Huracan preview")
        requireFile(File(aventadorPreviewPath.get()), "Aventador preview")
        requireFile(File(alfaPreviewPath.get()), "Alfa Romeo preview")
        requireFile(File(supraPreviewPath.get()), "Toyota Supra preview")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

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

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(::load)
}
val fmodSdkDirectory = localProperties.getProperty("fmod.sdk.dir")
    ?.takeIf(String::isNotBlank)
    ?.let(::File)
    ?.absoluteFile

val requiredFmodAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val generatedFmodSdk = layout.buildDirectory.dir("generated/fmodSdk")
val generatedFmodAssets = layout.buildDirectory.dir("generated/fmodAssets")

fun fmodSdkFile(relativePath: String): File =
    fmodSdkDirectory?.resolve(relativePath) ?: rootProject.file("missing-fmod-sdk/$relativePath")

val fmodJarSource = fmodSdkFile("api/lowlevel/lib/fmod.jar")
val fmodHeaderSource = fmodSdkFile("api/lowlevel/inc/fmod_common.h")
val privateAudioBanks = rootProject.file("audio_banks")
val privateFmodBanks = privateAudioBanks.resolve("fmod")
val privateCarPreviews = privateAudioBanks.resolve("car_previews")
val assettoStringsBank = privateFmodBanks.resolve("common.strings.bank")
val assettoCommonBank = privateFmodBanks.resolve("common.bank")
val assettoSkylineBank = privateFmodBanks.resolve("ks_nissan_skyline_r34.bank")
val skylinePreview = privateCarPreviews.resolve("nissan_skyline_r34.jpg")
val huracanBank = privateFmodBanks.resolve("fx_lamborghini_huracan_trofeo_evo2.bank")
val huracanPreview = privateCarPreviews.resolve("huracan_trofeo_evo2.jpg")
val aventadorBank = privateFmodBanks.resolve("tr_lamborghini_aventador_sv.bank")
val aventadorPreview = privateCarPreviews.resolve("aventador_sv.jpg")
val alfaBank = privateFmodBanks.resolve("ks_alfa_romeo_4c.bank")
val alfaPreview = privateCarPreviews.resolve("alfa_romeo_4c.jpg")
val supraBank = privateFmodBanks.resolve("zesty_toyota_supra_mk4_shuto_street.bank")
val supraPreview = privateCarPreviews.resolve("toyota_supra_mk4.jpg")

val validateFmodInputs = tasks.register<ValidateFmodInputsTask>("validateFmodInputs") {
    fmodSdkPath.set(fmodSdkDirectory?.path ?: "")
    skylineStringsBankPath.set(assettoStringsBank.path)
    skylineCommonBankPath.set(assettoCommonBank.path)
    skylineBankPath.set(assettoSkylineBank.path)
    skylinePreviewPath.set(skylinePreview.path)
    huracanBankPath.set(huracanBank.path)
    huracanPreviewPath.set(huracanPreview.path)
    aventadorBankPath.set(aventadorBank.path)
    aventadorPreviewPath.set(aventadorPreview.path)
    alfaBankPath.set(alfaBank.path)
    alfaPreviewPath.set(alfaPreview.path)
    supraBankPath.set(supraBank.path)
    supraPreviewPath.set(supraPreview.path)
    sourceFiles.from(
        fmodJarSource,
        fmodHeaderSource,
        fmodSdkFile("api/studio/inc/fmod_studio.hpp"),
        requiredFmodAbis.flatMap { abi ->
            listOf(
                fmodSdkFile("api/lowlevel/lib/$abi/libfmod.so"),
                fmodSdkFile("api/studio/lib/$abi/libfmodstudio.so"),
            )
        },
        assettoStringsBank,
        assettoCommonBank,
        assettoSkylineBank,
        skylinePreview,
        huracanBank,
        huracanPreview,
        aventadorBank,
        aventadorPreview,
        alfaBank,
        alfaPreview,
        supraBank,
        supraPreview,
    )
}

val prepareFmodSdk = tasks.register<Sync>("prepareFmodSdk") {
    dependsOn(validateFmodInputs)
    from(fmodSdkFile("api/lowlevel/inc")) { into("include/core") }
    from(fmodSdkFile("api/studio/inc")) { into("include/studio") }
    from(fmodJarSource) { into("java") }
    requiredFmodAbis.forEach { abi ->
        from(fmodSdkFile("api/lowlevel/lib/$abi/libfmod.so")) { into("jniLibs/$abi") }
        from(fmodSdkFile("api/studio/lib/$abi/libfmodstudio.so")) { into("jniLibs/$abi") }
    }
    into(generatedFmodSdk)
}

val prepareFmodAssets = tasks.register<Sync>("prepareFmodAssets") {
    dependsOn(validateFmodInputs)
    from(assettoStringsBank) { into("fmod") }
    from(assettoCommonBank) { into("fmod") }
    from(assettoSkylineBank) { into("fmod") }
    from(huracanBank) { into("fmod") }
    from(aventadorBank) { into("fmod") }
    from(alfaBank) { into("fmod") }
    from(supraBank) { into("fmod") }
    from(skylinePreview) {
        rename { "nissan_skyline_r34.jpg" }
        into("car_previews")
    }
    from(huracanPreview) {
        rename { "huracan_trofeo_evo2.jpg" }
        into("car_previews")
    }
    from(aventadorPreview) {
        rename { "aventador_sv.jpg" }
        into("car_previews")
    }
    from(alfaPreview) {
        rename { "alfa_romeo_4c.jpg" }
        into("car_previews")
    }
    from(supraPreview) {
        rename { "toyota_supra_mk4.jpg" }
        into("car_previews")
    }
    into(generatedFmodAssets)
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
    dependsOn(prepareFmodSdk, prepareFmodAssets)
}

tasks.configureEach {
    if (name.contains("CMake", ignoreCase = true) || name.contains("ExternalNativeBuild", ignoreCase = true)) {
        dependsOn(prepareFmodSdk)
    }
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

        ndk {
            abiFilters += requiredFmodAbis
        }

        externalNativeBuild {
            cmake {
                arguments += "-DFMOD_STAGED_DIR=${generatedFmodSdk.get().asFile.absolutePath.replace('\\', '/')}"
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror=return-type")
            }
        }
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
    sourceSets.getByName("main").assets.directories.add(generatedFmodAssets.get().asFile.path)
    sourceSets.getByName("main").jniLibs.directories.add(generatedFmodSdk.get().dir("jniLibs").asFile.path)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources {
        noCompress += "bank"
    }
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
    // FmodNativeBridge deliberately calls org.fmod.FMOD through reflection, so JVM unit tests
    // remain compilable before the user supplies the proprietary SDK. Packaging tasks validate
    // and stage this runtime-only JAR together with the native binaries.
    runtimeOnly(files(generatedFmodSdk.map { it.file("java/fmod.jar") }))
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
