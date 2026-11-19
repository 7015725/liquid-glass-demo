import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

data class FlowglassSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun environmentSigning(): FlowglassSigning? {
    val path = System.getenv("FLOWGLASS_KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: return null
    val storePassword = System.getenv("FLOWGLASS_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: return null
    val keyAlias = System.getenv("FLOWGLASS_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: return null
    val keyPassword = System.getenv("FLOWGLASS_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: return null
    val storeFile = File(path)
    if (!storeFile.isFile) return null
    return FlowglassSigning(storeFile, storePassword, keyAlias, keyPassword)
}

fun localSigning(): FlowglassSigning? {
    val propertiesFile = File(System.getProperty("user.home"), ".flowglass/signing/signing.properties")
    if (!propertiesFile.isFile) return null
    val properties = Properties().apply { propertiesFile.inputStream().use { load(it) } }
    val storeFileValue = properties.getProperty("storeFile")?.takeIf { it.isNotBlank() } ?: return null
    val storeFile = File(storeFileValue).let { if (it.isAbsolute) it else File(propertiesFile.parentFile, storeFileValue) }
    if (!storeFile.isFile) return null
    val storePassword = properties.getProperty("storePassword")?.takeIf { it.isNotBlank() } ?: return null
    val keyAlias = properties.getProperty("keyAlias")?.takeIf { it.isNotBlank() } ?: return null
    val keyPassword = properties.getProperty("keyPassword")?.takeIf { it.isNotBlank() } ?: return null
    return FlowglassSigning(storeFile, storePassword, keyAlias, keyPassword)
}

val flowglassSigning = environmentSigning() ?: localSigning()
if (flowglassSigning == null) {
    logger.warn("FlowGlass signing: no fixed key found in FLOWGLASS_KEYSTORE_PATH or ~/.flowglass/signing/signing.properties; falling back to debug signing.")
}

android {
    namespace = "com.flowglass.demo"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.flowglass.demo"
        minSdk = 26
        targetSdk = 37
        versionCode = 76
        versionName = "0.8.0-alpha38"
        androidResources.localeFilters += arrayOf("zh", "en")
    }

    signingConfigs {
        create("release") {
            if (flowglassSigning != null) {
                storeFile = flowglassSigning.storeFile
                storePassword = flowglassSigning.storePassword
                keyAlias = flowglassSigning.keyAlias
                keyPassword = flowglassSigning.keyPassword
                storeType = "JKS"
            }
        }
    }

    buildTypes {
        debug {
            if (flowglassSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = if (flowglassSigning != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
        }
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt"
            )
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Compose tests use a process-wide recomposer; isolate test classes so a later
        // Robolectric class cannot inherit a paused/finished Compose clock.
        unitTests.all { it.forkEvery = 1 }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("flowglass-${output.versionName.get()}.apk")
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.kyant.shapes)
    implementation(project(":backdrop"))
    implementation("org.mozilla:rhino:1.9.2")
    implementation(libs.libxposed.service)
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
