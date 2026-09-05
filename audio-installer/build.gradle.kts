plugins {
    alias(libs.plugins.android.application)
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

    signingConfigs {
        // The BYD sideload path rejects unsigned APKs. Keep the same stable local certificate
        // convention as the dashboard so reinstalling this helper does not require a manual
        // uninstall first.
        getByName("debug") { enableV2Signing = true }
    }


    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
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

}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("engine-sounds-audio-installer-${variant.name}.apk")
        }
    }
}
