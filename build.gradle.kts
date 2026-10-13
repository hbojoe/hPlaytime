plugins {
    id("java-library")
    id("com.gradleup.shadow") version "8.3.5"
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
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

val pluginVersion = version.toString()
val apiVersion = "1.19"

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
