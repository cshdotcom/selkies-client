import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:2.3.13")
                implementation("io.ktor:ktor-client-websockets:2.3.13")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:2.3.13")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.camera:camera-core:1.3.4")
                implementation("androidx.camera:camera-camera2:1.3.4")
                implementation("androidx.camera:camera-lifecycle:1.3.4")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:2.3.13")
                implementation(compose.desktop.currentOs)
                implementation("org.bytedeco:javacv:1.5.12")
                implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12")
                implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12:linux-x86_64")
            }
        }
    }
}

// 通用桌面 fat JAR 所需的全部平台原生库
val desktopAllNatives: Configuration by configurations.creating
dependencies {
    desktopAllNatives("org.bytedeco:javacpp:1.5.12:linux-x86_64")
    desktopAllNatives("org.bytedeco:javacpp:1.5.12:linux-arm64")
    desktopAllNatives("org.bytedeco:javacpp:1.5.12:windows-x86_64")
    desktopAllNatives("org.bytedeco:javacpp:1.5.12:macosx-x86_64")
    desktopAllNatives("org.bytedeco:javacpp:1.5.12:macosx-arm64")
    desktopAllNatives("org.bytedeco:ffmpeg:7.1.1-1.5.12:linux-x86_64")
    desktopAllNatives("org.bytedeco:ffmpeg:7.1.1-1.5.12:linux-arm64")
    desktopAllNatives("org.bytedeco:ffmpeg:7.1.1-1.5.12:windows-x86_64")
    desktopAllNatives("org.bytedeco:ffmpeg:7.1.1-1.5.12:macosx-x86_64")
    desktopAllNatives("org.bytedeco:ffmpeg:7.1.1-1.5.12:macosx-arm64")
    // Skiko（Compose Desktop 渲染层）全平台原生库
    desktopAllNatives("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.18")
    desktopAllNatives("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:0.8.18")
    desktopAllNatives("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.18")
    desktopAllNatives("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.8.18")
    desktopAllNatives("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.8.18")
}

android {
    namespace = "com.selkies.client"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.selkies.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 20101
        versionName = "2.1.1"
        ndkVersion = ""
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.keystore")
            storePassword = "selkies2024"
            keyAlias = "selkies"
            keyPassword = "selkies2024"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

compose.desktop {
    application {
        mainClass = "com.selkies.client.MainKt"
        nativeDistributions {
            packageName = "Selkies-Desktop"
            packageVersion = "2.1.1"
            description = "Selkies 远程桌面原生客户端"
            vendor = "Selkies Client"
        }
    }
}

// 桌面通用 fat JAR：包含全部平台原生库（x86_64 + arm64）
val desktopFatJar = tasks.register<Jar>("desktopFatJar") {
    group = "distribution"
    description = "构建包含全部平台原生库的通用桌面 JAR"
    archiveBaseName.set("Selkies-Desktop")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.selkies.client.MainKt"
        attributes["Implementation-Title"] = "Selkies Desktop Client"
        attributes["Implementation-Version"] = "2.1.1"
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/**/module-info.class")
    exclude("module-info.class")
    dependsOn(tasks.named("compileKotlinDesktop"))
    from(project.layout.buildDirectory.dir("classes/kotlin/desktop/main"))
    from(project.layout.buildDirectory.dir("resources/desktop"))
    from(configurations.named("desktopRuntimeClasspath").get().map { if (it.isDirectory) it else zipTree(it) })
    from(desktopAllNatives.map { if (it.isDirectory) it else zipTree(it) })
}

// 无头协议端到端验证（连接真实服务器验证协议链路）
val probeProtocol = tasks.register<JavaExec>("probeProtocol") {
    group = "verification"
    description = "无头端到端协议验证：握手→SETTINGS→MODE→START_VIDEO→视频帧"
    mainClass.set("com.selkies.client.tools.ProtocolProbe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    if (project.hasProperty("probeUrl")) systemProperty("probeUrl", project.property("probeUrl").toString())
    if (project.hasProperty("probeUser")) systemProperty("probeUser", project.property("probeUser").toString())
    if (project.hasProperty("probePass")) systemProperty("probePass", project.property("probePass").toString())
    if (project.hasProperty("probeTimeout")) systemProperty("probeTimeout", project.property("probeTimeout").toString())
}

// 连接层回归测试（静默死亡修复 / 主动关闭守卫 / 长稳保持）
tasks.register<JavaExec>("probeConnection") {
    group = "verification"
    description = "连接层回归：local-abrupt / local-clean / local-guard / real-hold"
    mainClass.set("com.selkies.client.tools.ConnectionProbe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("mode", "url", "holdSecs", "user", "pass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

// 无头视频解码验证（真实 DesktopVideoSink 全链路）
tasks.register<JavaExec>("probeVideoDecode") {
    group = "verification"
    description = "真实 FFmpeg 解码链路验证：帧进入 → 解码 → bitmapFlow 发布"
    mainClass.set("com.selkies.client.tools.VideoDecodeProbe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("holdSecs", "url", "user", "pass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}
