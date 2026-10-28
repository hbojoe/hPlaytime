plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.3.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.19-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.2")
    implementation("com.mysql:mysql-connector-j:9.2.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("org.slf4j:slf4j-jdk14:2.0.16")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("io.papermc.paper:paper-api:1.19-R0.1-SNAPSHOT")
    testImplementation("me.clip:placeholderapi:2.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

val pluginVersion = version.toString()
val apiVersion = "1.19"
val releaseTestClasspath = sourceSets.test.get().runtimeClasspath

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion(providers.gradleProperty("minecraftVersion").getOrElse("1.21.10"))
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to pluginVersion, "apiVersion" to apiVersion)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("com.zaxxer.hikari", "com.hboj.hPlaytime.libs.hikari")
        relocate("org.yaml.snakeyaml", "com.hboj.hPlaytime.libs.yaml")
        relocate("org.slf4j", "com.hboj.hPlaytime.libs.slf4j")
        relocate("com.google.gson", "com.hboj.hPlaytime.libs.gson")
        mergeServiceFiles()
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }

    jar { archiveClassifier.set("plain") }

    test {
        useJUnitPlatform()
        dependsOn(shadowJar)
        systemProperty("hplaytime.releaseJar", shadowJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("hplaytime.testClasspath", releaseTestClasspath.asPath)
    }

    build {
        dependsOn(shadowJar)
    }
}

tasks.register("buildReleaseJars") {
    group = "build"
    description = "Builds the universal hPlaytime release jar."
    dependsOn(tasks.named("shadowJar"))
}
