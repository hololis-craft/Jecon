import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
    `maven-publish`
}

group = "jp.jyn"
version = "2.2.1"

val projectUrl = "https://github.com/HimaJyun/Jecon"
val projectDescription = "Jecon is a simple economy plugin."
val relocBase = "jp.jyn.jecon.lib"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://himajyun.github.io/mvn-repo/")
    maven("https://jitpack.io")
    maven("https://repo.codemc.io/repository/creatorfromhell/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.16")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

tasks.processResources {
    val props = mapOf(
        "version"     to project.version.toString(),
        "url"         to projectUrl,
        "description" to projectDescription
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveBaseName = "Jecon"
    dependencies {
        include(dependency("com.zaxxer:HikariCP"))
    }
    relocate("com.zaxxer.hikari",  "$relocBase.hikari")
    minimize()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// lib profile 相当 (API 配布用 JAR — .yml 除外、シェードなし)
val libJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Library JAR without bundled dependencies"
    archiveClassifier = "lib"
    from(sourceSets.main.get().output)
    exclude("**/*.yml")
}

publishing {
    publications {
        create<MavenPublication>("lib") {
            groupId    = project.group.toString()
            artifactId = "Jecon"
            version    = project.version.toString()
            artifact(libJar)
            artifact(tasks.named("javadocJar"))
            artifact(tasks.named("sourcesJar"))
            pom {
                name        = "Jecon"
                description = projectDescription
                url         = projectUrl
                licenses {
                    license {
                        name = "The Apache Software License, Version 2.0"
                        url  = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "localRepo"
            url  = uri("${rootProject.projectDir}/mvn-repo")
        }
        maven {
            name = "GitHubPackages"
            url  = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "hololis-craft/Jecon"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

tasks.javadoc {
    options {
        encoding = "UTF-8"
        (this as StandardJavadocDocletOptions).charSet("UTF-8")
    }
}
