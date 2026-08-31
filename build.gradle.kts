plugins {
    `java-library`
    `maven-publish`
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "org.github.erictowns"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/spring") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-ui:1.7.0")
    implementation("com.aliyun.oss:aliyun-sdk-oss:3.17.4")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Local vector stores are runtime/generated data. Publishing them would make the SDK
// artifact several gigabytes and can leave the jar task appearing to hang.
tasks.processResources {
    exclude("vec/**")
}

tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
    archiveClassifier.set("boot")
    mainClass.set("veclite.VecLiteApplication")
}

tasks.getByName<Jar>("jar") {
    enabled = true
    archiveClassifier.set("")
}

tasks.named<Test>("test") {
    description = "运行快速单元测试与功能回归测试（排除 Benchmark、Stress、Accuracy 等大型测试）。"
    useJUnitPlatform {
        excludeTags("benchmark", "stress", "accuracy", "manual")
    }
    minHeapSize = "512m"
    maxHeapSize = "2g"
}

tasks.register<Test>("benchmark") {
    description = "运行 SQ8 及各架构版本的检索性能、QPS 与内存基准测试。"
    group = "verification"
    useJUnitPlatform {
        includeTags("benchmark")
    }
    minHeapSize = "2g"
    maxHeapSize = "6g"
}

tasks.register<Test>("stressTest") {
    description = "运行 1核1G 极限硬件受限及高负荷并发压力测试。"
    group = "verification"
    useJUnitPlatform {
        includeTags("stress")
    }
    minHeapSize = "2g"
    maxHeapSize = "6g"
}

tasks.register<Test>("accuracyTest") {
    description = "运行基于 Ground Truth 数据集的召回率与向量计算精度校验。"
    group = "verification"
    useJUnitPlatform {
        includeTags("accuracy")
    }
    minHeapSize = "1g"
    maxHeapSize = "2g"
}

tasks.register<Test>("v24ResourceBenchmark") {
    description = "在单核、1 GB 资源预算下运行 Veclite V2.4 容量与性能压测。"
    group = "verification"
    useJUnitPlatform()
    include("**/V24ResourcePerformanceBenchmarkTest.class")
    minHeapSize = "128m"
    maxHeapSize = "384m"
    maxParallelForks = 1
    systemProperty("veclite.benchmark.scale", providers.gradleProperty("benchmarkScale").getOrElse(""))
    systemProperty("veclite.benchmark.reportOnly", providers.gradleProperty("benchmarkReportOnly").getOrElse("false"))
    systemProperty("veclite.benchmark.failure", providers.gradleProperty("benchmarkFailure").getOrElse(""))
    jvmArgs(
        "-XX:ActiveProcessorCount=1",
        "-XX:MaxDirectMemorySize=512m",
        "-XX:+UseSerialGC"
    )
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "veclite"
            version = project.version.toString()

            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
        }
    }
    repositories {
        mavenLocal()
    }
}
