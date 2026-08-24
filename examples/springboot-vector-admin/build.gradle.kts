plugins {
    java
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "org.github.erictowns.examples"
version = "1.0.0"

java { sourceCompatibility = JavaVersion.VERSION_17 }
repositories { mavenLocal(); mavenCentral() }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.github.erictowns:veclite:1.0.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
tasks.withType<Test> { useJUnitPlatform() }
