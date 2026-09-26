import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jongsun.runcal"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jongsun.runcal"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // local.properties의 NOTION_API_TOKEN을 빌드 시점에 BuildConfig로 굽는다.
        // 이 저장소는 public이므로 토큰 값 자체는 절대 커밋되지 않는 local.properties에만 있다.
        val localProperties = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "NOTION_API_TOKEN",
            "\"${localProperties.getProperty("NOTION_API_TOKEN", "")}\"",
        )
        // 공공데이터포털(data.go.kr) 인증키 — 공휴일/24절기/음력 조회용. NOTION_API_TOKEN과 같은 방식.
        buildConfigField(
            "String",
            "DATA_GO_KR_API_KEY",
            "\"${localProperties.getProperty("DATA_GO_KR_API_KEY", "")}\"",
        )
        // Gemini API 키(자연어 명령). 이 APK는 개인용이라는 전제로 BuildConfig에 굽는다.
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"",
        )
        // Google Cloud Console에서 만든 "Web application" 타입 OAuth 클라이언트 ID.
        // Credential Manager의 GetSignInWithGoogleOption(serverClientId=...)에 필요하다 —
        // Android 클라이언트 ID가 아니라 반드시 Web 클라이언트 ID를 써야 한다.
        buildConfigField(
            "String",
            "DRIVE_WEB_CLIENT_ID",
            "\"${localProperties.getProperty("DRIVE_WEB_CLIENT_ID", "")}\"",
        )
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.squareup.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}