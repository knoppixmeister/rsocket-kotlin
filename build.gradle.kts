/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.jfrog.bintray.gradle.*
import com.jfrog.bintray.gradle.tasks.*
import org.gradle.api.publish.maven.internal.artifact.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jfrog.gradle.plugin.artifactory.dsl.*

buildscript {
    repositories {
        mavenCentral()
    }
    val kotlinVersion: String by rootProject
    val kotlinxAtomicfuVersion: String by rootProject

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$kotlinxAtomicfuVersion")
    }
}

val kotlinxAtomicfuVersion: String by rootProject

plugins {
    id("com.github.ben-manes.versions")

    //needed to add classpath to script
    id("com.jfrog.bintray") apply false
    id("com.jfrog.artifactory") apply false
}

allprojects {
    repositories {
        jcenter()
        maven("https://dl.bintray.com/kotlin/kotlinx")
        mavenCentral()
    }
}

val idea = System.getProperty("idea.active") == "true"

val isMainHost = System.getProperty("isMainHost") == "true"

val Project.publicationNames: Array<String>
    get() {
        val publishing: PublishingExtension by extensions
        val all = publishing.publications.names
        //publish js, jvm, metadata and kotlinMultiplatform only once
        return when {
            isMainHost -> all
            else       -> all - "js" - "jvm" - "metadata" - "kotlinMultiplatform"
        }.toTypedArray()
    }

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            if (!project.name.startsWith("rsocket")) return@configure //manual config of others

            jvm {
                compilations.all {
                    kotlinOptions { jvmTarget = "1.6" }
                }
                testRuns.all {
                    executionTask.configure {
                        enabled = isMainHost
                        jvmArgs("-Xmx4g", "-XX:+UseParallelGC")
                    }
                }
            }

            if (project.name == "rsocket-transport-ktor-server") return@configure //server is jvm only

            js {
                useCommonJs()
                //configure running tests for JS
                nodejs {
                    testTask {
                        enabled = isMainHost
                        useMocha {
                            timeout = "600s"
                        }
                    }
                }
                browser {
                    testTask {
                        enabled = isMainHost
                        useKarma {
                            useConfigDirectory(rootDir.resolve("js").resolve("karma.config.d"))
                            useChromeHeadless()
                        }
                    }
                }
            }

            //native targets configuration
            if (idea) {
                //if using idea, use only one native target same as host, DON'T PUBLISH FROM IDEA!!!
                val os = System.getProperty("os.name")
                when {
                    os == "Linux"            -> linuxX64("native")
                    os.startsWith("Windows") -> mingwX64("native")
                    os.startsWith("Mac")     -> macosX64("native")
                }
            } else {
                val nativeTargets = mutableListOf(
                    linuxX64(), macosX64(),
                    iosArm32(), iosArm64(), iosX64(),
                    watchosArm32(), watchosArm64(), watchosX86(),
                    tvosArm64(), tvosX64()
                )

                //windows target isn't supported by ktor-network
                if (project.name != "rsocket-transport-ktor" && project.name != "rsocket-transport-ktor-client") nativeTargets += mingwX64()

                val nativeMain by sourceSets.creating
                val nativeTest by sourceSets.creating

                nativeTargets.forEach {
                    sourceSets["${it.name}Main"].dependsOn(nativeMain)
                    sourceSets["${it.name}Test"].dependsOn(nativeTest)
                }
            }

            //run tests on release + mimalloc to reduce tests execution time
            //compilation is slower in that mode, so for local dev it's better to comment it
            targets.all {
                if (this is KotlinNativeTargetWithTests<*>) {
                    binaries.test(listOf(RELEASE))
                    testRuns.all { setExecutionSourceFrom(binaries.getTest(RELEASE)) }
                    compilations.all {
                        kotlinOptions.freeCompilerArgs += "-Xallocator=mimalloc"
                    }
                }
            }
        }

        extensions.configure<KotlinMultiplatformExtension> {
//        explicitApiWarning() //TODO change to strict before release
            sourceSets.all {
                languageSettings.apply {
                    progressiveMode = true
                    languageVersion = "1.4"
                    apiVersion = "1.4"

                    useExperimentalAnnotation("kotlin.RequiresOptIn")

                    if (name.contains("test", ignoreCase = true) || project.name == "rsocket-test") {
                        useExperimentalAnnotation("kotlin.time.ExperimentalTime")
                        useExperimentalAnnotation("kotlin.ExperimentalStdlibApi")
                        useExperimentalAnnotation("kotlinx.coroutines.ExperimentalCoroutinesApi")
                        useExperimentalAnnotation("kotlinx.coroutines.InternalCoroutinesApi")
                        useExperimentalAnnotation("kotlinx.coroutines.ObsoleteCoroutinesApi")
                        useExperimentalAnnotation("kotlinx.coroutines.FlowPreview")
                        useExperimentalAnnotation("io.ktor.util.KtorExperimentalAPI")
                        useExperimentalAnnotation("io.ktor.util.InternalAPI")
                    }
                }
            }

            if (project.name != "rsocket-test") {
                sourceSets["commonTest"].dependencies {
                    implementation(project(":rsocket-test"))
                }
            }

            //fix atomicfu for examples and playground
            if ("examples" in project.path || project.name == "playground") {
                sourceSets["commonMain"].dependencies {
                    implementation("org.jetbrains.kotlinx:atomicfu:$kotlinxAtomicfuVersion")
                }
            }
        }
    }
}


//publication
subprojects {

    val versionSuffix: String? by project
    if (versionSuffix != null) {
        project.version = project.version.toString() + versionSuffix
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            targets.all {
                mavenPublication {
                    pom {
                        name.set(project.name)
                        description.set(project.description)
                        url.set("http://rsocket.io")

                        licenses {
                            license {
                                name.set("The Apache Software License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("whyoleg")
                                name.set("Oleg Yukhnevich")
                                email.set("whyoleg@gmail.com")
                            }
                            developer {
                                id.set("OlegDokuka")
                                name.set("Oleh Dokuka")
                                email.set("oleh.dokuka@icloud.com")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/rsocket/rsocket-kotlin.git")
                            developerConnection.set("scm:git:https://github.com/rsocket/rsocket-kotlin.git")
                            url.set("https://github.com/rsocket/rsocket-kotlin")
                        }
                    }
                }
            }
        }
    }
}

val bintrayUser: String? by project
val bintrayKey: String? by project
if (bintrayUser != null && bintrayKey != null) {

    //configure artifactory
    subprojects {
        plugins.withId("com.jfrog.artifactory") {
            configure<ArtifactoryPluginConvention> {
                setContextUrl("https://oss.jfrog.org")

                publish(delegateClosureOf<PublisherConfig> {
                    repository(delegateClosureOf<DoubleDelegateWrapper> {
                        setProperty("repoKey", "oss-snapshot-local")
                        setProperty("username", bintrayUser)
                        setProperty("password", bintrayKey)
                        setProperty("maven", true)
                    })
                    defaults(delegateClosureOf<groovy.lang.GroovyObject> {
                        invokeMethod("publications", publicationNames)
                    })
                })

                val buildNumber: String? by project

                if (buildNumber != null) {
                    clientConfig.info.buildNumber = buildNumber
                }
            }
        }
    }

    //configure bintray / maven central
    val sonatypeUsername: String? by project
    val sonatypePassword: String? by project
    if (sonatypeUsername != null && sonatypePassword != null) {
        subprojects {
            plugins.withId("com.jfrog.bintray") {
                extensions.configure<BintrayExtension> {
                    user = bintrayUser
                    key = bintrayKey
                    setPublications(*publicationNames)

                    publish = true
                    override = true
                    pkg.apply {

                        repo = "RSocket"
                        name = "rsocket-kotlin"

                        setLicenses("Apache-2.0")

                        issueTrackerUrl = "https://github.com/rsocket/rsocket-kotlin/issues"
                        websiteUrl = "https://github.com/rsocket/rsocket-kotlin"
                        vcsUrl = "https://github.com/rsocket/rsocket-kotlin.git"

                        githubRepo = "rsocket/rsocket-kotlin"
                        githubReleaseNotesFile = "README.md"

                        version.apply {
                            name = project.version.toString()
                            vcsTag = project.version.toString()
                            released = java.util.Date().toString()

                            gpg.sign = true

                            mavenCentralSync.apply {
                                user = sonatypeUsername
                                password = sonatypePassword
                            }
                        }
                    }
                }

                //workaround for https://github.com/bintray/gradle-bintray-plugin/issues/229
                tasks.withType<BintrayUploadTask> {
                    doFirst {
                        val publishing: PublishingExtension by extensions
                        val names = publicationNames
                        publishing.publications
                            .filterIsInstance<MavenPublication>()
                            .filter { it.name in names }
                            .forEach { publication ->
                                val moduleFile = buildDir.resolve("publications/${publication.name}/module.json")
                                if (moduleFile.exists()) {
                                    publication.artifact(object : FileBasedMavenArtifact(moduleFile) {
                                        override fun getDefaultExtension() = "module"
                                    })
                                }
                            }
                    }
                }
            }
        }
    }
}
