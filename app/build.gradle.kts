@file:Suppress("UnstableApiUsage")

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep this module's output under the repository-root build/ directory.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("app"))

val appName = "Cheese"
val appVersionName = "1.0"

// Commit count, so every build of a given tree gets the same versionCode.
// The exec is allowed to fail: a fresh clone with no commits yet must still
// build, and then there is simply nothing to count.
val gitVersionCode = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 1
}.getOrElse {
    logger.warn("git rev-list failed (${it.message}); versionCode falls back to 1")
    1
}

val buildInfoSrc = layout.buildDirectory.dir("generated/source/buildInfo")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "generateBuildInfo"
    val outputDirectory = buildInfoSrc
    outputs.dir(outputDirectory)
    outputs.upToDateWhen { false }
    doLast {
        val directory = outputDirectory.get().asFile.resolve("com/cheese/app")
        directory.mkdirs()
        directory.listFiles()?.forEach { stale -> if (stale.isFile) stale.delete() }
        val timeMillis = System.currentTimeMillis()
        val label = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .format(Date(timeMillis))
        directory.resolve("BuildInfo.kt").writeText(
            buildString {
                appendLine("package com.cheese.app")
                appendLine()
                appendLine("/** Generated per build; the about page shows this. */")
                appendLine("object BuildInfo {")
                appendLine("    const val BUILD_TIME_EPOCH_MILLIS: Long = ${timeMillis}L")
                appendLine("    const val BUILD_TIME_LABEL: String = \"$label\"")
                appendLine("}")
            },
        )
    }
}

android {
    namespace = "com.cheese.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    defaultConfig {
        applicationId = "com.cheese.app"
        minSdk = 31
        targetSdk = 37
        versionCode = gitVersionCode
        versionName = appVersionName
    }
    androidResources {
        localeFilters += listOf("en", "zh")
    }
    sourceSets {
        named("main") {
            kotlin.directories.add(buildInfoSrc.get().asFile.absolutePath)
        }
    }

    /* 没有提供签名配置时用 debug 签名，保证 assembleRelease 也能直接装。 */
    val properties = Properties()
    runCatching { properties.load(project.rootProject.file("local.properties").inputStream()) }
    val keystorePath = (properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH"))
        ?.trim()?.takeIf { it.isNotEmpty() }
    val keystorePwd = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
    val pwd = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
    val keystoreFile = keystorePath?.let(::file)?.takeIf { it.isFile && it.length() > 0L }
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
        }
        debug {
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
        }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        disable += "ChromeOsAbiSupport"
    }
    packaging {
        // legacy 解压：lib*.so 在安装时落到 nativeLibraryDir，可直接执行
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
        dex {
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            // arm64 only: that is what the phones this targets run.
            include("arm64-v8a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.named("preBuild") {
    dependsOn(generateBuildInfo)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
}
