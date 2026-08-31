plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

val buildNumberFile = file("build-number.properties")
val buildNumberProperties = Properties()
if (buildNumberFile.exists()) {
    buildNumberFile.inputStream().use { buildNumberProperties.load(it) }
}

val stampCarBuild = gradle.startParameter.projectProperties["carApk"] == "true"
val isMainAppAssembling = gradle.startParameter.taskNames.any { taskName ->
    taskName == "assemble" ||
        taskName == ":assemble" ||
        taskName.startsWith(":mobile:assemble") ||
        taskName.startsWith("mobile:assemble") ||
        (taskName.startsWith("assemble") && !taskName.contains(":"))
}
val isFinalMainAppBuild = isMainAppAssembling && stampCarBuild

val storedBuildNumber = buildNumberProperties.getProperty("buildNumber", "1").toInt()
val stampedBuildNumber = if (isFinalMainAppBuild) storedBuildNumber + 1 else storedBuildNumber

fun validateFinalCarCatalog(catalogFile: File) {
    if (!catalogFile.isFile) {
        throw GradleException(
            "Final car APK build requires ${catalogFile.path}; run the release catalog assembly first.",
        )
    }

    val root = try {
        JsonSlurper().parse(catalogFile)
    } catch (error: Exception) {
        throw GradleException("Final car APK build found an unreadable car catalog at ${catalogFile.path}.", error)
    }
    val catalog = root as? Map<*, *> ?: throw GradleException(
        "Final car APK build requires the car catalog root to be a JSON object.",
    )
    val rootBytes = catalogFile.readBytes()
    val maximumRootBytes = 512 * 1024
    val operationalRuntimeBytes = 8 * 1024 * 1024
    val maximumRuntimeBytes = 16 * 1024 * 1024
    val maximumFamilyRuntimeBytes = 4 * 1024 * 1024
    if (rootBytes.size > maximumRootBytes) {
        throw GradleException(
            "Final car APK build requires a root catalog <= $maximumRootBytes bytes; found ${rootBytes.size}.",
        )
    }
    if (catalog.keys != setOf("schema", "catalogVersion", "cars", "families")) {
        throw GradleException(
            "Final car APK build requires the catalog fields schema, catalogVersion, cars, and families.",
        )
    }
    if (catalog["schema"] != "byd-car-atlas-catalog-v2") {
        throw GradleException(
            "Final car APK build requires schema byd-car-atlas-catalog-v2, found ${catalog["schema"]}.",
        )
    }
    if (catalog["catalogVersion"]?.toString()?.toIntOrNull() != 2) {
        throw GradleException(
            "Final car APK build requires catalogVersion 2, found ${catalog["catalogVersion"]}.",
        )
    }

    val cars = catalog["cars"] as? List<*> ?: throw GradleException(
        "Final car APK build requires a cars array.",
    )
    val families = catalog["families"] as? List<*> ?: throw GradleException(
        "Final car APK build requires a families array.",
    )
    if (cars.size != 36 || families.size != 32) {
        throw GradleException(
            "Final car APK build requires exactly 36 cars and 32 families; found ${cars.size} cars and ${families.size} families.",
        )
    }

    fun stringIds(items: List<*>, field: String, label: String): List<String> {
        return items.mapIndexed { index, item ->
            val objectValue = item as? Map<*, *> ?: throw GradleException(
                "Final car APK build found a non-object at $label[$index].",
            )
            objectValue[field] as? String ?: throw GradleException(
                "Final car APK build found a missing or non-string $label[$index].$field.",
            )
        }
    }

    fun uniqueStringIds(items: List<*>, field: String, label: String): Set<String> {
        val ids = stringIds(items, field, label)
        if (ids.any(String::isBlank) || ids.toSet().size != ids.size) {
            throw GradleException(
                "Final car APK build requires unique non-empty $field values in $label.",
            )
        }

        return ids.toSet()
    }

    uniqueStringIds(cars, "id", "cars")
    val familyIds = uniqueStringIds(families, "id", "families")
    val carFamilyIds = stringIds(cars, "audioProgramFamilyId", "cars").toSet()
    if (carFamilyIds.any(String::isBlank)) {
        throw GradleException(
            "Final car APK build requires non-empty audioProgramFamilyId values in cars.",
        )
    }
    if (carFamilyIds != familyIds) {
        throw GradleException(
            "Final car APK build requires every runtime family to be referenced by at least one car.",
        )
    }

    fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    val assetsRoot = catalogFile.parentFile.canonicalFile
    val expectedRuntimeAssets = mutableSetOf<String>()
    var familyRuntimeBytes = 0L
    families.forEachIndexed { index, item ->
        val family = item as? Map<*, *> ?: throw GradleException(
            "Final car APK build found a non-object at families[$index].",
        )
        val expectedKeys = setOf(
            "id",
            "assetDirectory",
            "packRequirement",
            "runtimeAssetName",
            "runtimeBytes",
            "runtimeSha256",
            "eagerCapabilities",
        )
        if (family.keys != expectedKeys) {
            throw GradleException(
                "Final car APK build found an invalid lazy runtime descriptor at families[$index].",
            )
        }
        val familyId = family["id"] as? String ?: throw GradleException(
            "Final car APK build found a missing family id at families[$index].",
        )
        val assetName = family["runtimeAssetName"] as? String ?: throw GradleException(
            "Final car APK build found a missing runtime asset name at families[$index].",
        )
        val expectedAssetName = "families/$familyId.json"
        if (assetName != expectedAssetName || !expectedRuntimeAssets.add(assetName)) {
            throw GradleException(
                "Final car APK build found an unsafe or duplicate runtime asset name at families[$index].",
            )
        }
        val declaredBytes = (family["runtimeBytes"] as? Number)?.toLong()
            ?: throw GradleException(
                "Final car APK build found a missing runtime byte count at families[$index].",
            )
        val declaredSha = family["runtimeSha256"] as? String ?: throw GradleException(
            "Final car APK build found a missing runtime SHA-256 at families[$index].",
        )
        if (declaredBytes < 1 || declaredBytes > maximumFamilyRuntimeBytes ||
            !Regex("[0-9a-f]{64}").matches(declaredSha)
        ) {
            throw GradleException(
                "Final car APK build found an invalid runtime size or SHA-256 at families[$index].",
            )
        }
        val capabilities = family["eagerCapabilities"] as? Map<*, *> ?: throw GradleException(
            "Final car APK build found no eager capabilities at families[$index].",
        )
        if (capabilities.keys != setOf("perspectives", "effectControls")) {
            throw GradleException(
                "Final car APK build found invalid eager capability fields at families[$index].",
            )
        }
        val perspectives = capabilities["perspectives"] as? List<*> ?: throw GradleException(
            "Final car APK build found no eager perspective list at families[$index].",
        )
        if (perspectives.any { it !is String } || perspectives.toSet().size != perspectives.size || perspectives.isEmpty()) {
            throw GradleException(
                "Final car APK build found invalid eager perspectives at families[$index].",
            )
        }
        val effectControls = capabilities["effectControls"] as? Map<*, *> ?: throw GradleException(
            "Final car APK build found no eager effect controls at families[$index].",
        )
        if (effectControls.keys != perspectives.toSet()) {
            throw GradleException(
                "Final car APK build found mismatched eager effect controls at families[$index].",
            )
        }
        perspectives.forEach { rawPerspective ->
            val perspective = rawPerspective as String
            val controls = effectControls[perspective] as? Map<*, *> ?: throw GradleException(
                "Final car APK build found invalid eager controls for $perspective.",
            )
            val triggers = controls["runtimeTriggers"] as? List<*> ?: throw GradleException(
                "Final car APK build found no eager triggers for $perspective.",
            )
            if (controls.keys != setOf("hasTurboEvent", "runtimeTriggers") ||
                controls["hasTurboEvent"] !is Boolean ||
                triggers.any { it !is String || it.isBlank() } ||
                triggers.toSet().size != triggers.size
            ) {
                throw GradleException(
                    "Final car APK build found invalid eager effect controls for $perspective.",
                )
            }
        }
        val runtimeFile = File(assetsRoot, assetName).canonicalFile
        if (runtimeFile.parentFile != File(assetsRoot, "families").canonicalFile || !runtimeFile.isFile) {
            throw GradleException(
                "Final car APK build is missing the descriptor runtime asset $assetName.",
            )
        }
        val runtimeBytes = runtimeFile.readBytes()
        if (runtimeBytes.size.toLong() != declaredBytes || sha256(runtimeBytes) != declaredSha) {
            throw GradleException(
                "Final car APK build found a runtime asset that differs from its root descriptor: $assetName.",
            )
        }
        familyRuntimeBytes += runtimeBytes.size
    }
    val familyDirectory = File(assetsRoot, "families")
    val actualRuntimeAssets = familyDirectory.listFiles()?.filter(File::isFile)?.map {
        "families/${it.name}"
    }?.toSet() ?: emptySet()
    if (actualRuntimeAssets != expectedRuntimeAssets) {
        throw GradleException(
            "Final car APK build found missing or orphan family runtime assets.",
        )
    }
    val totalRuntimeBytes = rootBytes.size.toLong() + familyRuntimeBytes
    if (totalRuntimeBytes > maximumRuntimeBytes) {
        throw GradleException(
            "Final car APK build requires root plus family runtimes <= $maximumRuntimeBytes bytes; found $totalRuntimeBytes.",
        )
    }
    if (totalRuntimeBytes > operationalRuntimeBytes) {
        logger.warn(
            "Final car APK runtime catalog exceeds the $operationalRuntimeBytes-byte operational target: $totalRuntimeBytes.",
        )
    }
}

if (isFinalMainAppBuild) {
    validateFinalCarCatalog(file("src/main/assets/car_catalog/atlas-catalog-v2.json"))
}

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
        assetNames = listOf(
            "aventadorintidle.wav",
            "aventadorintaccf2825.wav", "aventadorintaccf3685.wav",
            "aventadorintacc5250.wav", "aventadorintacc5600.wav", "aventadorintacc6000.wav",
            "aventadorintacc6501.wav", "aventadorintacc7103.wav", "aventadorintacc7592.wav",
            "aventadorintacc8294.wav",
            "aventadorintoff3165.wav", "aventadorintoff4309.wav", "aventadorintoff5853.wav",
            "aventadorintoff6300.wav", "aventadorintoff7200.wav", "aventadorintoff8373.wav",
            "transmission.wav", "GEAR_CHANGING_CABIN.wav",
        ),
        previewSource = rootProject.file("audio_samples/tr_lamborghini_aventador_sv/preview1.jpg"),
        previewAssetName = "lamborghini_aventador_sv.jpg",
    ),
    LocalEngineProfileAssets(
        assetDirectory = "nissan_skyline_r34",
        sourceDirectory = rootProject.file("audio_samples/fx_nissan_skyline_r34/converted"),
        assetNames = listOf(
            "rb26_4_ex_idle.wav",
            "rb26_2_in_on_verylow.wav",
            "rb26_in_2_onverylow.wav",
            "rb26_2_in_on_verylow2.wav",
            "rb26_2_in_on_low3.wav",
            "rb26_in_2_onlow.wav",
            "rb26_in_2_onmid.wav",
            "rb26_2_in_on_mid3.wav",
            "rb26_in_2_onmid2.wav",
            "rb26_in_2_onhigh.wav",
            "rb26_in_on_high2.wav",
            "rb26_in_2_onhigh2.wav",
            "rb26_in_on_veryhigh.wav",
            "rb26_4_ex_off_verylow.wav",
            "rb26_ex_5_offverylow.wav",
            "rb26_ex_5_offlow.wav",
            "rb26_ex_5_offmid.wav",
            "rb26_3_revlim_EQ.wav",
            "s1_turbo.wav",
            "flutter_4.wav",
            "rb26_bf1.wav",
            "rb26_bf2.wav",
            "sin5.wav",
            "gearup.wav",
            "gearupEXT.wav",
            "geardnEXT.wav",
            "missgear.wav",
            "RB26DET_pop_1.wav",
            "RB26DET_pop_2.wav",
            "RB26DET_pop_3.wav",
            "rb26_pop1.wav",
            "rb26_pop2.wav",
            "s1_pop.wav",
        ),
        previewSource = rootProject.file("audio_samples/fx_nissan_skyline_r34/preview1.jpg"),
        previewAssetName = "nissan_skyline_r34.jpg",
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
        include(
            "s009_ex_l3.wav",
            "s013_ex_idle.wav",
            "s016_ex_c2.wav",
            "s042_ex_l6.wav",
            "s058_ex_c1e.wav",
            "s083_ex_c6.wav",
            "s088_ex_l1b.wav",
            "s123_ex_l4.wav",
            "s131_ex_l5.wav",
            "s136_ex_l2a_far.wav",
            "s138_ex_c4.wav",
            "s145_ex_c3.wav",
        )
        into("sample_engine/lamborghini_huracan_trofeo_evo2")
    }
    from(rootProject.file("audio_samples/tr_lamborghini_aventador_sv/converted_exterior")) {
        include(
            "ex_aventador_idle.wav",
            "ex_aventador_onlow.wav",
            "ex_aventador_onmid.wav",
            "ex_aventador_onmidhigh.wav",
            "ex_aventador_onhigh.wav",
            "ex_aventador_onveryhigh.wav",
            "ex_aventador_offverylow.wav",
            "ex_aventador_offmid.wav",
            "ex_aventador_offveryhigh.wav",
        )
        into("sample_engine/lamborghini_aventador_sv")
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
    from(rootProject.file("audio_samples/fx_lamborghini_huracan_trofeo_evo2/converted")) {
        include("fx_shift_up.wav", "fx_shift_down.wav")
        into("sample_engine/shared/huracan_shift_sounds")
    }
    into(generatedSampleEngineAssets)
}

if (isFinalMainAppBuild) {
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
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.getByName("main").assets.srcDir(generatedSampleEngineAssets)
    sourceSets.getByName("test").resources.srcDir(
        rootProject.file("tools/profile_generation/fixtures"),
    )
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
    implementation(project(":audio-pack-contract"))
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
