import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.kapt)
}

group = "com.keyqiang.spw"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // 由 SPW 宿主提供，绝不打包进插件。
    //
    // isTransitive = false 是必须的：dev20 的 api 仍以 compile 作用域挂着
    // Compose Multiplatform + Salt UI（dev15~dev19 播放界面扩展点的遗留），
    // 而它们最终指向 androidx.*（只在 Google Maven），本插件一个都用不到。
    // 关掉传递依赖后，我们显式只取真正需要的 PF4J。
    compileOnly(libs.spw.workshop.api) { isTransitive = false }
    compileOnly(libs.pf4j)

    // PF4J 自带注解处理器，生成 META-INF/extensions.idx；缺它扩展点不会被发现
    kapt(libs.pf4j)

    implementation(kotlin("stdlib"))
}

// ---------------- 插件打包 ----------------

val pluginId = "com.keyqiang.spw.listenstats"
val pluginClass = "com.keyqiang.spw.listenstats.ListenStatsPlugin"
val pluginName = "听歌统计"
val pluginProvider = "HOTBOY-xlqaimyx"
val pluginDescription = "记录每首曲目的收听时长并生成统计，可定时上报到你自己的服务端"
// 本插件自己的仓库（上游 API 只作为依赖，见 NOTICE）
val pluginRepoUrl = "https://github.com/HOTBOY-xlqaimyx/spw-listenstats"

// 两份清单必须完全一致，原因见 plugin 任务里的注释
val pluginManifest = mapOf(
    "Plugin-Class" to pluginClass,
    "Plugin-Id" to pluginId,
    "Plugin-Name" to pluginName,
    "Plugin-Version" to project.version.toString(),
    "Plugin-Provider" to pluginProvider,
    "Plugin-Description" to pluginDescription,
    "Plugin-Open-Source-Url" to pluginRepoUrl,
    "Plugin-Has-Config" to "true",
)

tasks.named<Jar>("jar") {
    // 自测代码只用于本地验证，不进插件包
    exclude("com/keyqiang/spw/listenstats/selftest/**")

    manifest {
        attributes(pluginManifest)
    }
}

tasks.register<Jar>("plugin") {
    group = "build"
    description = "打包成可导入 SPW 的插件 zip（classes/ + lib/）"

    archiveFileName.set("ListenStats-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))

    // ⚠️ 关键：PF4J 对「目录型插件」读的描述符是 <插件目录>/META-INF/MANIFEST.MF。
    // zip 被 PF4J 解包后，插件目录顶层的 META-INF/MANIFEST.MF 正是本 Jar 任务自己的清单，
    // 如果不在这里也写上 Plugin-*，PF4J 会读到一个空描述符（id=null、class=org.pf4j.Plugin）
    // 并以 InvalidPluginDescriptorException: Field 'id' cannot be empty 拒绝加载。
    // 官方 README 的模板只给内层 jar 设 manifest，正是踩这个坑（已用模拟宿主实测复现）。
    manifest {
        attributes(pluginManifest)
    }

    into("classes") {
        with(tasks.named<Jar>("jar").get())
    }
    dependsOn(configurations.runtimeClasspath)
    into("lib") {
        from({
            configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }
        })
    }
    archiveExtension.set("zip")

    // 合规：分发包里随附 LICENSE 与 NOTICE。
    // lib/ 内含 kotlin-stdlib（Apache-2.0），分发时必须随附许可证并保留声明。
    from(File(projectDir.parentFile, "LICENSE"))
    from(File(projectDir.parentFile, "NOTICE"))

    doLast {
        // fnOS 挂载卷上生成的文件权限会退化成 000（cp/Files.copy 还会把它带到别处），
        // 显式放权，保证产物在宿主/容器里都能被读取
        archiveFile.get().asFile.setReadable(true, false)
    }
}

tasks.register<JavaExec>("selftest") {
    group = "verification"
    description = "运行不依赖宿主的纯逻辑自测（JSON / 统计引擎 / 存储 / 上报）"

    dependsOn(tasks.named("classes"))
    mainClass.set("com.keyqiang.spw.listenstats.selftest.SelfTestKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
