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
                // v2.7.5：音频备用解码引擎（FFmpeg Opus 软解，与桌面端同一验证过的代码路径）。
                // 原生库（android-arm64 .so）由 extractFfmpegAndroidNatives 任务从下面
                // ffmpegAndroidNatives 配置提取进 jniLibs，APK 内由 System.loadLibrary 加载。
                implementation("org.bytedeco:javacpp:1.5.12")
                implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:2.3.13")
                implementation(compose.desktop.currentOs)
                implementation("org.bytedeco:javacv:1.5.12")
                implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12")
                implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12:linux-x86_64")
                // 桌面手柄：LWJGL GLFW gamepad（Windows XInput/macOS/Linux 全平台）
                val lwjgl = "3.3.4"
                implementation("org.lwjgl:lwjgl:$lwjgl")
                implementation("org.lwjgl:lwjgl-glfw:$lwjgl")
                listOf("natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64", "natives-macos", "natives-macos-arm64").forEach { n ->
                    runtimeOnly("org.lwjgl:lwjgl:$lwjgl:$n")
                    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjgl:$n")
                }
            }
        }
    }
}

// v2.7.5：Android FFmpeg 原生库（Opus 软解备用引擎）——仅提取 avcodec/avutil/swresample
// 及其 JNI 包装（Opus 解码所需最小集，~31MB 未压缩；useLegacyPackaging 压缩存储控 APK 体积）
val ffmpegAndroidNatives: Configuration by configurations.creating
dependencies {
    ffmpegAndroidNatives("org.bytedeco:ffmpeg:7.1.1-1.5.12:android-arm64")
    ffmpegAndroidNatives("org.bytedeco:javacpp:1.5.12:android-arm64")
}

val extractFfmpegAndroidNatives = tasks.register<Copy>("extractFfmpegAndroidNatives") {
    group = "build"
    description = "从 javacpp android-arm64 制品提取 Opus 解码所需最小 .so 集到 jniLibs"
    // 注意：保留 zip 内 lib/arm64-v8a/ 相对路径（jniLibs.srcDir 指向 generated/ffmpeg-jniLibs/lib，
    // AGP 即从 arm64-v8a/ 目录识别 ABI）
    into(layout.buildDirectory.dir("generated/ffmpeg-jniLibs"))
    from(ffmpegAndroidNatives.map { if (it.isDirectory) it else zipTree(it) })
    include(
        "lib/arm64-v8a/libjnijavacpp.so",
        "lib/arm64-v8a/libavutil.so",
        "lib/arm64-v8a/libjniavutil.so",
        "lib/arm64-v8a/libswresample.so",
        "lib/arm64-v8a/libjniswresample.so",
        "lib/arm64-v8a/libavcodec.so",
        "lib/arm64-v8a/libjniavcodec.so"
    )
}

// AGP 合并 jniLibs 前必须先提取（debug/release 两个变体都覆盖）
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(extractFfmpegAndroidNatives)
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
        versionCode = 20709
        versionName = "2.7.9"
        ndkVersion = ""
    }
    // v2.7.5：FFmpeg 原生库压缩存储（APK ~26MB 而非 ~48MB；安装时解压到本地）
    sourceSets {
        getByName("main") {
            // 提取任务保留 lib/arm64-v8a/ 前缀 → srcDir 指向 lib/，AGP 从 arm64-v8a/ 识别 ABI
            jniLibs.srcDir(layout.buildDirectory.dir("generated/ffmpeg-jniLibs/lib"))
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        jniLibs.useLegacyPackaging = true
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
            packageVersion = "2.7.8"
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
        attributes["Implementation-Version"] = "2.7.8"
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

// GUI E2E 驱动（Xvfb 下 AWT Robot 自动化）
tasks.register<JavaExec>("guiProbe") {
    group = "verification"
    description = "GUI 自动化步骤执行器：-Pscript=\"sleep:4000;shot:01;click:640,300\""
    mainClass.set("com.selkies.client.tools.GuiProbe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    systemProperty("java.awt.headless", "false")
    if (project.hasProperty("script")) systemProperty("script", project.property("script").toString())
}

// v2.6.0 综合端到端验证（剪贴板回环/音频/多编码器/录屏/虚拟手柄/竖屏分辨率）
tasks.register<JavaExec>("probeV26") {
    group = "verification"
    description = "v2.6.0 无头综合验证：剪贴板回环 + 音频 + 多编码器解码 + 录屏 + 虚拟手柄 + 竖屏"
    mainClass.set("com.selkies.client.tools.V26Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

// 录屏专项诊断（逐秒对比 收帧/写帧）
tasks.register<JavaExec>("probeRec") {
    group = "verification"
    description = "录屏专项诊断：逐秒打印收帧 vs 写帧，定位 0 帧断点"
    mainClass.set("com.selkies.client.tools.RecProbe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

// v2.7.0 综合端到端验证（服务器能力/麦克风门控/剪贴板轮询去重/音频看门狗/录屏自动分段）
tasks.register<JavaExec>("probeV27") {
    group = "verification"
    description = "v2.7.0 无头综合验证：服务器能力 + 麦克风门控 + 剪贴板可靠同步 + 音频看门狗 + 录屏分段续录"
    mainClass.set("com.selkies.client.tools.V27Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

tasks.register<JavaExec>("probeV273") {
    group = "verification"
    description = "v2.7.3 无头综合验证：麦克风locked语义 + 剪贴板往返 + 音频活性"
    mainClass.set("com.selkies.client.tools.V273Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

tasks.register<JavaExec>("probeV274") {
    group = "verification"
    description = "v2.7.4 无头综合验证：locked语义回归 + 剪贴板 + 手柄 + 文件往返 + 音频活性"
    mainClass.set("com.selkies.client.tools.V274Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

tasks.register<JavaExec>("probeV275") {
    group = "verification"
    description = "v2.7.5 无头综合验证：麦克风工具栏门控回归 + 自定义存储根 + SAF路径解析 + 剪贴板"
    mainClass.set("com.selkies.client.tools.V275Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

tasks.register<JavaExec>("probeV278") {
    group = "verification"
    description = "v2.7.8 无头综合验证：认证闭环 + 渲染停滞看门狗 + 剪贴板图像 + 分辨率关键帧 + 只读切换 + 麦克风恢复 + 音频计量"
    mainClass.set("com.selkies.client.tools.V278Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

tasks.register<JavaExec>("probeV276") {
    group = "verification"
    description = "v2.7.6 无头综合验证：viewer DISABLED 恢复链 + 键盘宽度公式 + csd拆分 + 把手淡出 + 门控/剪贴板回归"
    mainClass.set("com.selkies.client.tools.V276Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

tasks.register<JavaExec>("probeV277") {
    group = "verification"
    description = "v2.7.7 无头综合验证：viewer+slot 手柄控制端 + 认证弹窗链路 + 解码停滞看门狗 + switchRole + 剪贴板图片"
    mainClass.set("com.selkies.client.tools.V277Probe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
    for (k in listOf("probeUrl", "probeUser", "probePass")) {
        if (project.hasProperty(k)) systemProperty(k, project.property(k).toString())
    }
}

tasks.register<JavaExec>("probeAudioSink") {
    group = "verification"
    description = "v2.7.1 音频播放管线自检：Opus 解码 → PCM → SourceDataLine（不依赖服务器）"
    mainClass.set("com.selkies.client.tools.AudioSinkProbe")
    val desktopCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = desktopCompilation.output.allOutputs + configurations.getByName("desktopRuntimeClasspath")
}
