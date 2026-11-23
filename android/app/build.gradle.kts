plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.calendaralarm"

    // All deployment-specific values come from Gradle properties (~/.gradle/gradle.properties,
    // android/gradle.properties or -P flags / ORG_GRADLE_PROJECT_* env vars). Nothing personal
    // is hardcoded. See docs/CONFIGURATION.md.
    fun prop(name: String, default: String): String =
        (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default
    compileSdk = 35

    defaultConfig {
        applicationId = prop("CALALARM_APPLICATION_ID", "io.github.calendaralarm")
        minSdk = 26
        targetSdk = 35
        versionCode = 66
        versionName = "2.8.0"

        // Self-hosted update feed (version.json + APKs). Empty = in-app updater disabled.
        buildConfigField("String", "UPDATE_SERVER_URL", "\"${prop("CALALARM_UPDATE_SERVER_URL", "")}\"")
        // Default backend URL shown on first start (user can change it in Settings).
        buildConfigField("String", "ALARM_API_URL", "\"${prop("CALALARM_API_URL", "https://alarm-api.example.com")}\"")
        // Comma-separated base64 SPKI SHA-256 pins. Empty = normal system CA validation only.
        // SHA-256 of your release signing certificate (hex). Empty = no extra pin for in-app updates.
        buildConfigField("String", "UPDATE_SIGNER_SHA256", "\"${prop("CALALARM_UPDATE_SIGNER_SHA256", "")}\"")
        buildConfigField("String", "CERT_PINS", "\"${prop("CALALARM_CERT_PINS", "")}\"")
        buildConfigField("String", "APP_NAME", "\"CalendarAlarm\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = project.findProperty("KEYSTORE_PATH") as String?
            val keystorePassword = project.findProperty("KEYSTORE_PASSWORD") as String?
            val keyAlias = project.findProperty("KEY_ALIAS") as String?
            val keyPassword = project.findProperty("KEY_PASSWORD") as String?

            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    // H-08 fix: Enable lint for release builds
    lint {
        checkReleaseBuilds = true
        abortOnError = false  // warn but don't fail the build
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            // Robolectric moet app-resources (strings, drawables) laden voor
            // notificatie-render tests (v2.6.13 NotificationPolicyTest).
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // H-08: no unused BuildConfig fields
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // H-08: no unused API_TOKEN BuildConfig field — token is generated at runtime
            // and stored in EncryptedSharedPreferences
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.compose.runtime:runtime-livedata:1.7.6")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security - EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Ktor Client only (pull model — no in-app server)
    val ktorVersion = "3.0.1"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.mockk:mockk-android:1.13.13")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    testImplementation("androidx.test:runner:1.6.2")
    // TestListenableWorkerBuilder for AlarmSyncWorker tests
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation("io.mockk:mockk-android:1.13.13")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
}

// JaCoCo test coverage
apply(plugin = "jacoco")

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*", "kotlinx.coroutines.debug.*")
        destinationFile = layout.buildDirectory.file("jacoco/${this@configureEach.name}.exec").get().asFile
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) {
        include("*.exec")
    })
    val javaClasses = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*Test*")
    }
    val kotlinClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*Test*")
    }
    classDirectories.setFrom(files(javaClasses, kotlinClasses))
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
}
