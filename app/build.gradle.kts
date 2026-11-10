import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.invilink.xboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.invilink.xboard"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("releaseExternal") {
            storeFile = file(System.getenv("XBOARD_KEYSTORE") ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = System.getenv("XBOARD_STORE_PASSWORD") ?: System.getenv("XBOARD_STORE_PASSWORD_FILE")?.let { file(it).readText().trimEnd('\r', '\n') }
            keyAlias = System.getenv("XBOARD_KEY_ALIAS")
            keyPassword = System.getenv("XBOARD_KEY_PASSWORD") ?: System.getenv("XBOARD_KEY_PASSWORD_FILE")?.let { file(it).readText().trimEnd('\r', '\n') }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("releaseExternal")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("com.github.mwiede:jsch:0.2.21")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

// Android's mockable jar contains stubs for these utilities. Compile their actual
// SDK sources for local JVM tests; this does not affect the application artifact.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Android/Sdk"
val sdkSources = file("$androidSdk/sources/android-37.0")
val compileTestAndroidUtilities = tasks.register<JavaCompile>("compileTestAndroidUtilities") {
    val root = sdkSources
    source(files(
        "${root}/android/util/JsonReader.java", "${root}/android/util/JsonToken.java",
        "${root}/android/util/JsonScope.java", "${root}/android/util/MalformedJsonException.java",
        "${root}/android/util/Base64.java", "${root}/com/android/internal/util/StringPool.java",
        "${root}/android/ravenwood/annotation/RavenwoodKeepWholeClass.java",
        "${root}/android/compat/annotation/UnsupportedAppUsage.java",
    ).filter { it.isFile })
    source(fileTree("$root/org/json") { include("*.java") })
    options.sourcepath = files(root)
    doFirst { check(file("$sdkSources/android/util/JsonReader.java").isFile) { "Android SDK sources android-37.0 are required for local unit tests." } }
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("test-android-utilities"))
}
tasks.withType<Test>().configureEach {
    dependsOn(compileTestAndroidUtilities)
    doFirst { classpath = files(compileTestAndroidUtilities.get().destinationDirectory) + classpath }
}

val apkVersion = android.defaultConfig.versionName

tasks.configureEach {
    if (name == "preReleaseBuild") doFirst {
        val signing = android.signingConfigs.getByName("releaseExternal")
        check(signing.storeFile?.isFile == true) { "Release keystore not found." }
        check(!signing.storePassword.isNullOrBlank() && !signing.keyPassword.isNullOrBlank() && !signing.keyAlias.isNullOrBlank()) {
            "Set XBOARD_STORE_PASSWORD, XBOARD_KEY_PASSWORD and XBOARD_KEY_ALIAS for release signing."
        }
    }
}

// Only export a verified release signed by the certificate in the selected keystore.
tasks.register("exportReleaseApk") {
    dependsOn("assembleRelease")
    doLast {
        val source = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.toPath()
        val exportDir = System.getenv("XBOARD_EXPORT_DIRECTORY")?.let { file(it).toPath() }
            ?: rootProject.layout.projectDirectory.dir("../3Xclient_apk").asFile.toPath()
        val target = exportDir.resolve("3xBoard-$apkVersion.apk")
        val signing = android.signingConfigs.getByName("releaseExternal")
        val apksigner = System.getenv("ANDROID_APKSIGNER")
        check(!apksigner.isNullOrBlank() && file(apksigner).isFile) { "Set ANDROID_APKSIGNER to the SDK apksigner executable." }
        val keytool = file("${System.getProperty("java.home")}/bin/keytool")
        check(keytool.isFile) { "JDK keytool not found." }
        Files.createDirectories(exportDir)
        val staged = Files.createTempFile(exportDir, ".release-", ".apk")
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
            val certBuilder = ProcessBuilder(keytool.absolutePath, "-exportcert", "-keystore", signing.storeFile!!.absolutePath,
                "-alias", signing.keyAlias!!, "-storepass:env", "XBOARD_CERT_PASSWORD")
            certBuilder.environment()["XBOARD_CERT_PASSWORD"] = signing.storePassword!!
            val certProcess = certBuilder.redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val cert = certProcess.inputStream.use { it.readBytes() }
            check(certProcess.waitFor() == 0 && cert.isNotEmpty()) { "Unable to read the signing certificate from the keystore." }
            val expected = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
            val verify = ProcessBuilder(apksigner, "verify", "--verbose", "--print-certs", staged.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = verify.inputStream.bufferedReader().use { it.readText() }
            check(verify.waitFor() == 0) { "APK signature verification failed." }
            val digests = Regex("(?m)^Signer #(\\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\\s*$").findAll(output).toList()
            check(digests.size == 1 && digests.single().groupValues[1] == "1" &&
                digests.single().groupValues[2].equals(expected, ignoreCase = true)) {
                "APK must have exactly one signer matching the selected keystore certificate."
            }
            if (Files.exists(target)) {
                check(Files.isRegularFile(target)) { "Export target is not a regular file." }
                if (Files.mismatch(target, staged) != -1L) {
                    val previous = exportDir.resolve("previous")
                    Files.createDirectories(previous)
                    Files.copy(target, previous.resolve("3xBoard-previous-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.apk"))
                }
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}

tasks.register<Copy>("exportDebugApk") {
    dependsOn("assembleDebug")
    val assembledApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val exportDir = rootProject.layout.projectDirectory.dir("../3Xclient_apk")
    from(assembledApk)
    into(exportDir)
    rename { "3xBoard-$apkVersion-debug.apk" }
    outputs.upToDateWhen { false }
    doFirst {
        val target = exportDir.file("3xBoard-$apkVersion-debug.apk").asFile
        val current = target.takeIf { it.isFile } ?: exportDir.asFile.listFiles()
            ?.filter { it.isFile && ((it.name.startsWith("3xBoard-") && it.name.endsWith(".apk")) ||
                (it.name.startsWith("xBoard-") && it.name.endsWith("-debug.apk"))) }
            ?.maxByOrNull { it.lastModified() }
        val next = assembledApk.get().asFile
        if (current != null && Files.mismatch(current.toPath(), next.toPath()) != -1L) {
            val previousDir = exportDir.dir("previous").asFile.toPath()
            Files.createDirectories(previousDir)
            Files.copy(current.toPath(), previousDir.resolve("3xBoard-previous.apk"), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
