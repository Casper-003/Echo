plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp) // 搭载全新的 2.3.4 引擎
}

android {
    namespace = "com.example.echo"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.echo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "4.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("dev.chrisbanes.haze:haze:0.5.4")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation("androidx.compose.material:material-icons-extended:1.6.1")
    // 1. Compose 导航路由 (用于支持页面间的高级跳转)
    implementation("androidx.navigation:navigation-compose:2.8.0")
    // 2. Compose 动画扩展 (必须 >= 1.7.0 才能完美支持 SharedTransitionLayout 共享元素)
    implementation("androidx.compose.animation:animation:1.7.0")
// ARCore 原生库（直接用 ARCore，不依赖 sceneview）
implementation("com.google.ar:core:1.42.0")
    // Coil Compose 图片加载库 (用于渲染导入的户型底图)
    implementation("io.coil-kt:coil-compose:2.6.0")
    ksp(libs.androidx.room.compiler)
}
