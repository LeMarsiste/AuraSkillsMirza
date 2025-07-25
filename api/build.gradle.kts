plugins {
    `java-library`
    `maven-publish`
    signing
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:24.1.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(platform("org.junit:junit-bom:5.13.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.release.set(8)
}

tasks {
    javadoc {
        title = "AuraSkills API (${project.version})"
        options {
            (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
            overview("javadoc/overview.html")
            encoding("UTF-8")
            charset("UTF-8")
        }
    }

    test {
        useJUnitPlatform()
    }
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

if (project.properties.keys.containsAll(setOf("developerId", "developerUsername", "developerEmail", "developerUrl"))) {
    publishing {
        repositories {
            maven {
                name = "StagingDeploy"
                url = uri(layout.buildDirectory.dir("staging-deploy"))
            }
            if (project.properties.keys.containsAll(
                    setOf(
                        "sonatypeUsername",
                        "sonatypePassword"
                    )
                ) && project.version.toString().endsWith("-SNAPSHOT")
            ) {
                maven {
                    name = "Snapshot"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")

                    credentials {
                        username = project.property("sonatypeUsername").toString()
                        password = project.property("sonatypePassword").toString()
                    }
                }
            }
        }

        publications.create<MavenPublication>("mavenJava") {
            groupId = "dev.aurelium"
            artifactId = "auraskills-api"
            version = project.version.toString()

            pom {
                name.set("AuraSkills API")
                description.set("API for AuraSkills, the ultra-versatile RPG skills plugin for Minecraft")
                url.set("https://wiki.aurelium.dev/auraskills")
                licenses {
                    license {
                        name.set("The GNU General Public License, Version 3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.en.html")
                    }
                }
                developers {
                    developer {
                        id.set(project.property("developerId").toString())
                        name.set(project.property("developerUsername").toString())
                        email.set(project.property("developerEmail").toString())
                        url.set(project.property("developerUrl").toString())
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/Archy-X/AuraSkills.git")
                    developerConnection.set("scm:git:git://github.com/Archy-X/AuraSkills.git")
                    url.set("https://github.com/Archy-X/AuraSkills/tree/master")
                }
            }

            from(components["java"])
        }
    }

    signing {
        sign(publishing.publications.getByName("mavenJava"))
        isRequired = true
    }
}