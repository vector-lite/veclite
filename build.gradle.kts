plugins {
    `java-library`
    id("org.springframework.boot") version "2.7.18" apply false
    id("io.spring.dependency-management") version "1.1.4"
}

group = "com.hexin"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:2.7.18")
    }
}

dependencies {
    api("org.springframework.boot:spring-boot-autoconfigure")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("com.fasterxml.jackson.core:jackson-databind")

    compileOnly("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
