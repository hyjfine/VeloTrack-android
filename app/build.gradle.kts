import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun secretProperty(name: String): String =
    (project.findProperty(name) as String?)?.trim()
        ?: localProperties.getProperty(name)?.trim()
        ?: ""

android {
    namespace = "com.velotrack.velotrack"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.velotrack.velotrack"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.1"


        val geminiKey = secretProperty("GEMINI_API_KEY")
        val aiProxyUrl = secretProperty("AI_PROXY_URL")
        val googleMapsKey = secretProperty("GOOGLE_MAPS_API_KEY")
        val amapKey = secretProperty("AMAP_API_KEY")
        val mapProviderOverride = secretProperty("MAP_PROVIDER")
        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "AI_PROXY_URL", "\"${aiProxyUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${googleMapsKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "AMAP_API_KEY", "\"${amapKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "MAP_PROVIDER_OVERRIDE", "\"${mapProviderOverride.replace("\"", "\\\"")}\"")
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsKey
        manifestPlaceholders["AMAP_API_KEY"] = amapKey
    }

    signingConfigs {
        val storePath = secretProperty("RELEASE_STORE_FILE")
        val storePasswordValue = secretProperty("RELEASE_STORE_PASSWORD")
        val keyAliasValue = secretProperty("RELEASE_KEY_ALIAS")
        val keyPasswordValue = secretProperty("RELEASE_KEY_PASSWORD")
        if (storePath.isNotBlank() && storePasswordValue.isNotBlank() &&
            keyAliasValue.isNotBlank() && keyPasswordValue.isNotBlank()
        ) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            // Release 不内嵌 Gemini 服务端密钥；AI 必须经受控代理调用。
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("com.google.maps.android:maps-compose:8.4.0")
    implementation("com.amap.api:3dmap-location-search:11.1.001_loc11.1.001_sea9.7.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
