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
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.2")
    implementation("com.mysql:mysql-connector-j:9.2.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

val pluginVersion = version.toString()

fun registerMinecraftReleaseJar(taskSuffix: String, classifier: String, apiVersion: String): TaskProvider<Jar> {
    val resourceOutput = layout.buildDirectory.dir("generated/resources/$taskSuffix")
    val resourceProperties = mapOf("version" to pluginVersion, "apiVersion" to apiVersion)
    val processVariantResources = tasks.register<org.gradle.language.jvm.tasks.ProcessResources>("process${taskSuffix}Resources") {
        from(sourceSets.main.get().resources)
        destinationDir = resourceOutput.get().asFile
        filesMatching("plugin.yml") {
            expand(resourceProperties)
        }
    }

    return tasks.register<Jar>("shadowJar$taskSuffix") {
        group = "build"
        description = "Creates the shaded hPlaytime jar for Minecraft $apiVersion."
        archiveClassifier.set(classifier)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        dependsOn(tasks.named("classes"), processVariantResources)
        from(sourceSets.main.get().output.classesDirs)
        from(resourceOutput)
        from({
            configurations.runtimeClasspath.get()
                .filter { it.isFile && it.name.endsWith(".jar") }
                .map { zipTree(it) }
        })
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

val shadowJarMc26_1 = registerMinecraftReleaseJar("Mc26_1", "mc26.1", "26.1.2")
val shadowJarMc26_2 = registerMinecraftReleaseJar("Mc26_2", "mc26.2", "26.1.2")

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion(providers.gradleProperty("minecraftVersion").getOrElse("1.21.10"))
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to pluginVersion, "apiVersion" to "1.21.10")
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar, shadowJarMc26_1, shadowJarMc26_2)
    }
}

tasks.register("buildReleaseJars") {
    group = "build"
    description = "Builds the legacy, Minecraft 26.1, and Minecraft 26.2 release jars."
    dependsOn(tasks.named("shadowJar"), shadowJarMc26_1, shadowJarMc26_2)
}
