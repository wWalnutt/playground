import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"
}

group = "org.walnut"
version = "0.0.1-SNAPSHOT"
description = "playground"

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    add("implementation", "org.springframework.boot:spring-boot-starter")
    add("implementation", "org.springframework.boot:spring-boot-starter-web")
    add("implementation", "org.springframework.boot:spring-boot-starter-data-jpa")
    add("runtimeOnly", "com.h2database:h2")
    add("implementation", "org.jetbrains.kotlin:kotlin-reflect")
    add("implementation", kotlin("stdlib"))
    add("implementation", "org.springframework.kafka:spring-kafka:3.3.16")

    // Spring AI DeepSeek chat
    add("implementation", "org.springframework.ai:spring-ai-starter-model-deepseek")

    add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
    add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit5")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    add("testImplementation", "org.springframework.kafka:spring-kafka-test")
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
