plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.witzig0hast.cmfexport"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.witzig0hast.cmfexport"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed, non-secret key (password "android", like every Android debug key) so that
    // APKs built on any machine / in CI can be installed over each other.
    signingConfigs {
        create("shared") {
            storeFile = file("cmfexport.keystore")
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
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.health.connect:connect-testing:1.0.0-alpha04")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Robolectric would download the Android framework jar itself at test time; let Gradle
// fetch it (cached, retried) and run Robolectric offline against that file instead.
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
dependencies { robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7") }
tasks.withType<Test>().configureEach {
    val jars = robolectricRuntime
    inputs.files(jars)
    doFirst {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", jars.singleFile.parentFile.absolutePath)
        System.getenv("CMF_SERVER_URL")?.let { environment("CMF_SERVER_URL", it) }
    }
}
