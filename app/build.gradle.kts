import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Sentry DSN — override SPLITBILL_SENTRY_DSN_BLOB / MASK in local.properties to rotate.
val sentryDsnMaskSeed = localProperties.getProperty("SPLITBILL_SENTRY_DSN_MASK", "splitbill.sentry.v1")
val sentryDsnBlobEscaped = localProperties.getProperty(
    "SPLITBILL_SENTRY_DSN_BLOB",
    "GwQYGQdYRkMOSBVUX0UWSx1GU0pDCQ1DVVpUDxsRBgxGRUFKF1NARywGQFdYXVsbRVFdQUVIFkcFR14FBxMHGhhCShZLHREcDVwPHxofQ11BU1hVWh9FVVxFS0wbRQc=",
)

// Cloud backup proxy — same Vercel endpoint + obfuscated API key as FitBuddy.
// The proxy key only grants read/upsert-by-id (no delete). Atlas creds never ship in the app.
val cloudBackupBaseUrlRaw: String =
    System.getenv("CLOUD_BACKUP_BASE_URL")
        ?: localProperties.getProperty("CLOUD_BACKUP_BASE_URL", "https://fitbuddy-cloud-backup.vercel.app")
val backupApiKeyMaskSeed = "fitbuddy.backup.v1"
val backupApiKeyBlobEscaped = "VFlMVkBTUkBIVVACWxQSSxUFBwxMV0MAVxobVAcCCBQVT0QEUQ1MB0IFBh0eU1hTChZHGRMEB11HWhECBk4fWg=="
val mongoDbNameRaw: String =
    System.getenv("MONGO_DB_NAME")
        ?: localProperties.getProperty("MONGO_DB_NAME", "fitbuddy")

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val ciVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
val ciVersionName = project.findProperty("appVersionName") as String?

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.anant.splitbill"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.anant.splitbill"
        minSdk = 29
        targetSdk = 36
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "IS_FDROID", "false")
        buildConfigField("String", "SENTRY_DSN_BLOB", "\"${escapeBuildConfig(sentryDsnBlobEscaped)}\"")
        buildConfigField("String", "SENTRY_DSN_MASK", "\"${escapeBuildConfig(sentryDsnMaskSeed)}\"")
        buildConfigField("String", "BACKUP_API_KEY_BLOB", "\"${escapeBuildConfig(backupApiKeyBlobEscaped)}\"")
        buildConfigField("String", "BACKUP_API_KEY_MASK", "\"${escapeBuildConfig(backupApiKeyMaskSeed)}\"")
        buildConfigField("String", "CLOUD_BACKUP_BASE_URL", "\"${escapeBuildConfig(cloudBackupBaseUrlRaw)}\"")
        buildConfigField("String", "MONGO_DB_NAME", "\"${escapeBuildConfig(mongoDbNameRaw)}\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FDROID", "false")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FDROID", "true")
            versionCode = 1
            versionName = "1.0.0"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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
    installation {
        installOptions += listOf("--user", "0")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "META-INF/native-image/**"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
        }
    }
}

val fallbackApkVersionName = ciVersionName ?: "1.0.0-dev"
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName -> "SplitBill-${versionName ?: fallbackApkVersionName}.apk" }
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.logging.interceptor)
    implementation(libs.material)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.sentry.android)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20260719")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}
