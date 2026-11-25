// AgentForge 根构建：各模块共用的 Java 约定。
// JDK 25：bitcask / sandbox 走 FFM（java.lang.foreign），21 里还是 preview。
plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "io.ailink"
    version = "0.2.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:-options", "-Xmaxerrs", "2000"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // libbitcask.so 的位置：-Pbitcask.lib=/path/libbitcask.so 或环境变量 AGENTFORGE_BITCASK_LIB
        (findProperty("bitcask.lib") as String?)?.let { systemProperty("agentforge.bitcask.lib", it) }
    }

    dependencies {
        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        "implementation"(platform(libs.findLibrary("spring-boot-bom").get()))
        "testImplementation"(platform(libs.findLibrary("spring-boot-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }
}
