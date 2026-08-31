plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gabrielpc.audiopackcontract"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
