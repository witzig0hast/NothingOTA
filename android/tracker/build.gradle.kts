plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.witzig0hast.cmftrack"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.witzig0hast.cmftrack"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.0"
    }
    signingConfigs {
        create("shared") {
            storeFile = file("../app/cmfexport.keystore")
            storePassword = "android"
            keyAlias = "cmfexport"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.maplibre.gl:android-sdk:11.13.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.health.connect:connect-testing:1.0.0-alpha04")
}

val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
dependencies { robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7") }
tasks.withType<Test>().configureEach {
    val jars = robolectricRuntime
    inputs.files(jars)
    doFirst {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", jars.singleFile.parentFile.absolutePath)
    }
}
