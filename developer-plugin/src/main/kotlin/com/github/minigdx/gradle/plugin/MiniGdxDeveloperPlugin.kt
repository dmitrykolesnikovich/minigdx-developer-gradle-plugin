/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package com.github.minigdx.gradle.plugin

import com.github.minigdx.gradle.plugin.internal.MiniGdxException
import com.github.minigdx.gradle.plugin.internal.MiniGdxException.Companion.ISSUES
import com.github.minigdx.gradle.plugin.internal.Severity
import com.github.minigdx.gradle.plugin.internal.Solution
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import org.gradle.util.GradleVersion
import org.jetbrains.dokka.gradle.DokkaTask
import java.io.File
import java.net.URI

/**
 * Plugin for developers of MiniGDX project.
 *
 * It configures plugins used in the project that are commons to all projects
 * like the publication, kotlin version, ...
 */
class MiniGdxDeveloperPlugin : Plugin<Project> {

    private val classLoader = MiniGdxDeveloperPlugin::class.java.classLoader

    override fun apply(project: Project) {
        project.extensions.create("minigdxDeveloper", MiniGdxDeveloperExtension::class.java, project)
        configureGradleVersion(project)
        configureProjectVersionAndGroupId(project)
        configureProjectRepository(project)
        configureDokka(project)
        configurePublication(project)
        configureLinter(project)
        configureMakefile(project)
        configureGithubWorkflow(project)

        configureSonatype(project)
    }

    private fun configureGradleVersion(project: Project) {
        if (GradleVersion.current() < GradleVersion.version("6.8.2")) {
            throw MiniGdxException.create(
                severity = Severity.EASY,
                project = project,
                because = "The gradle version used is too old.",
                description = "The expected gradle version is 6.8.2.",
                solutions = listOf(Solution("Update the gradle-wrapper.properties with a newer version"))
            )
        }
    }

    private fun configureProjectVersionAndGroupId(project: Project) {
        var version = project.properties["version"] ?: DEFAULT_VERSION

        if (version == "unspecified") {
            version = DEFAULT_VERSION
        }

        project.version = version
        project.group = "com.github.minigdx"
    }

    private fun configurePublication(project: Project) {
        project.apply { it.plugin("maven-publish") }
        project.afterEvaluate {
            val ext = project.extensions.getByType(MiniGdxDeveloperExtension::class.java)
            project.extensions.configure(PublishingExtension::class.java) {
                // Configure publication (what to publish)
                it.publications.withType(MavenPublication::class.java).configureEach {
                    if (it.name != "pluginMaven") {
                        // TODO: [CACHE] Create variable. Push it outside lambda
                        it.artifact(project.tasks.getByName("javadocJar"))
                    }

                    it.pom {
                        it.name.set(ext.name)
                        it.description.set(ext.description)
                        it.licenses {
                            it.license {
                                it.name.set(ext.licence.name)
                                it.url.set(ext.licence.url)
                            }
                        }
                        it.url.set(ext.projectUrl)
                        it.issueManagement {
                            it.system.set("Github")
                            it.url.set(ext.projectUrl.map { url -> "$url/issues" })
                        }
                        it.scm {
                            it.connection.set(ext.projectUrl.map { url -> "$url/.git" })
                            it.url.set(ext.projectUrl)
                        }
                        it.developers { spec ->
                            ext.developers.forEach { dev ->
                                spec.developer {
                                    it.name.set(dev.name)
                                    it.email.set(dev.email)
                                    it.url.set(dev.url)
                                }
                            }
                        }

                    }
                }
            }
        }
        project.tasks.withType(PublishToMavenRepository::class.java).configureEach { publication ->
            publication.onlyIf {
                // publish on sonatype only if the username is configured.
                (publication.name.startsWith("sonatype") &&
                    // TODO: [CACHE] Might need to do something about that.
                    project.properties["sonatype.username"]?.toString()?.isNotBlank() == true) ||
                    !publication.name.startsWith("sonatype")
            }

        }
    }

    private fun configureDokka(project: Project) {
        // TODO - [CACHE] Dokka doesn't support Configuration Cache yet
        //      See: https://github.com/Kotlin/dokka/issues/2231
        project.apply { it.plugin("org.jetbrains.dokka") }
        project.tasks.register("javadocJar", Jar::class.java) {
            it.dependsOn(project.tasks.getByName("dokkaHtml"))
            it.archiveClassifier.set("javadoc")
            it.from(project.buildDir.resolve("dokka"))
        }

        project.tasks.withType(DokkaTask::class.java).whenTaskAdded { dokka ->
            dokka.notCompatibleWithConfigurationCache(
                "The dokka tasks are not compatible yet " +
                    "with the configuration cache."
            )
        }
    }

    private fun configureProjectRepository(project: Project) {
        project.repositories.mavenCentral()
        project.repositories.google()
        // Snapshot repository. Select only our snapshot dependencies
        project.repositories.maven {
            it.url = URI("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        }.mavenContent {
            it.includeVersionByRegex("com.github.minigdx", "(.*)", "LATEST-SNAPSHOT")
            it.includeVersionByRegex("com.github.minigdx.(.*)", "(.*)", "LATEST-SNAPSHOT")
        }
        project.repositories.mavenLocal()
    }

    private fun configureLinter(project: Project) {
        project.apply { it.plugin("org.jlleitschuh.gradle.ktlint") }
    }

    // TODO: [CACHE] Don't pass project any more to the method
    private fun copy(project: Project, filename: String, target: File) {
        val content = classLoader.getResourceAsStream(filename) ?: throw MiniGdxException.create(
            severity = Severity.GRAVE,
            project = project,
            because = "'$filename' file not found in the plugin jar! The plugin might have been incorrectly packaged.",
            description = "The plugin is trying to copy a resource that should has been packaged into the plugin " +
                "but is not. As this file is required, the plugin will stop.",
            solutions = listOf(Solution("An issue can be reported to the developer", ISSUES))
        )
        target.resolve(File(filename).name).apply {
            if (!exists()) createNewFile()
            writeBytes(content.readBytes())
        }
    }

    private fun configureGithubWorkflow(project: Project) {
        // The task is already registered
        if (project.rootProject.tasks.findByName("createGithubWorkflows") != null) {
            return
        }
        project.rootProject.tasks.register("createGithubWorkflows") {
            it.group = "minigdx-dev"
            it.description = "Copy default Github workflows inside this project."
            it.doLast {
                // TODO: [CACHE] Move target outside lambda
                val target = it.project.projectDir.resolve(".github/workflows")
                if (!target.exists()) {
                    it.project.mkdir(".github/workflows")
                }
                copy(project, "github/workflows/build.yml", target)
                copy(project, "github/workflows/publish-release.yml", target)
                copy(project, "github/workflows/publish-snapshot.yml", target)
            }
        }
    }

    private fun configureMakefile(project: Project) {
        // The task is already registered
        if (project.rootProject.tasks.findByName("createMakefile") != null) {
            return
        }
        project.rootProject.tasks.register("createMakefile") {
            it.group = "minigdx-dev"
            it.description = "Copy default Makefile inside this project."
            it.doLast {
                // TODO: [CACHE] move target out of lambda
                val target = it.project.projectDir
                copy(project, "Makefile", target)
            }
        }
    }

    private fun configureSonatype(project: Project) {
        if (project.properties["signing.base64.secretKey"] == null) {
            return
        }
        project.apply { it.plugin("org.gradle.signing") }
        val publications = project.extensions.getByType(PublishingExtension::class.java).publications
        project.extensions.getByType(PublishingExtension::class.java).repositories {
            // Configure where to publish
            it.maven {
                it.name = "sonatypeStaging"
                it.setUrl("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                it.credentials {
                    it.username = project.properties["sonatype.username"].toString()
                    it.password = project.properties["sonatype.password"].toString()
                }
            }

            it.maven {
                it.name = "sonatypeSnapshots"
                it.setUrl("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                it.credentials {
                    it.username = project.properties["sonatype.username"].toString()
                    it.password = project.properties["sonatype.password"].toString()
                }
            }
        }

        project.extensions.configure(SigningExtension::class.java) {
            it.sign(publications)
            it.useInMemoryPgpKeys(
                project.properties["signing.base64.secretKey"].toString(),
                project.properties["signing.password"].toString()
            )
        }
    }

    companion object {

        private const val DEFAULT_VERSION = "DEV-SNAPSHOT"
    }
}
