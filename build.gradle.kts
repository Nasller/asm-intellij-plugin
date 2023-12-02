import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun properties(key: String) = project.findProperty(key).toString()

buildscript {
    dependencies {
        classpath("org.ow2.asm:asm:9.5")
        classpath("org.ow2.asm:asm-commons:9.5")
    }
}

plugins {
    // Java support
    id("java")
    // Gradle IntelliJ Plugin
    id("org.jetbrains.intellij") version "1.16.1"
}

val artifactTypeAttribute = Attribute.of("artifactType", String::class.java)
val repackagedAttribute = Attribute.of("repackaged", Boolean::class.javaObjectType)

val repackage: Configuration by configurations.creating {
    attributes.attribute(repackagedAttribute, true)
}

group = properties("pluginGroup")
version = properties("pluginVersion")

// Configure project's dependencies
abstract class MyRepackager : TransformAction<TransformParameters.None> {
    @InputArtifact
    abstract fun getInputArtifact(): Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val input = getInputArtifact().get().asFile
        val output = outputs.file(
            input.name.let {
                if (it.endsWith(".jar"))
                    it.replaceRange(it.length - 4, it.length, "-repackaged.jar")
                else
                    "$it-repackaged"
            }
        )
        println("Repackaging ${input.absolutePath} to ${output.absolutePath}")
        ZipOutputStream(output.outputStream()).use { zipOut ->
            ZipFile(input).use { zipIn ->
                val entriesList = zipIn.entries().toList()
                val entriesSet = entriesList.mapTo(mutableSetOf()) { it.name }
                for (entry in entriesList) {
                    val newName = if (entry.name.contains("/") && !entry.name.startsWith("META-INF/")) {
                        "com/nasller/asm/libs/" + entry.name
                    } else {
                        entry.name
                    }
                    zipOut.putNextEntry(ZipEntry(newName))
                    if (entry.name.endsWith(".class")) {
                        val writer = ClassWriter(0)
                        val classReader = ClassReader(zipIn.getInputStream(entry))
                        classReader.accept(ClassRemapper(writer, object : Remapper() {
                            override fun map(internalName: String?): String? {
                                if (internalName == null) return null
                                return if (entriesSet.contains("$internalName.class") ||
                                    internalName.startsWith("org/objectweb/asm/")) {
                                    "com/nasller/asm/libs/$internalName"
                                } else internalName
                            }

                            override fun mapValue(value: Any?): Any {
                                val mapValue = super.mapValue(value)
                                return if(mapValue is String && mapValue.startsWith("org/objectweb/asm/")){
                                    "com/nasller/asm/libs/$mapValue"
                                } else mapValue
                            }
                        }), 0)
                        zipOut.write(writer.toByteArray())
                    } else {
                        zipIn.getInputStream(entry).copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
            zipOut.flush()
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    attributesSchema {
        attribute(repackagedAttribute)
    }
    artifactTypes.getByName("jar") {
        attributes.attribute(repackagedAttribute, false)
    }
    registerTransform(MyRepackager::class) {
        from.attribute(repackagedAttribute, false).attribute(artifactTypeAttribute, "jar")
        to.attribute(repackagedAttribute, true).attribute(artifactTypeAttribute, "jar")
    }

    repackage("org.ow2.asm:asm:9.5")
    repackage("org.ow2.asm:asm-commons:9.5")
    repackage("org.ow2.asm:asm-util:9.5")
    implementation(files(repackage.files))
}

// Configure Gradle IntelliJ Plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
intellij {
    pluginName.set(properties("pluginName"))
    version.set(properties("platformVersion"))
    type.set(properties("platformType"))
    sandboxDir.set("${rootProject.rootDir}/idea-sandbox")
    downloadSources.set(true)
    updateSinceUntilBuild.set(false)
    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
    plugins.set(properties("platformPlugins").split(',').map(String::trim).filter(String::isNotEmpty))
}

tasks {
    runIde {
        systemProperties["idea.is.internal"] = true
        jvmArgs("")
    }

    // Set the JVM compatibility versions
    properties("javaVersion").let {
        withType<JavaCompile> {
            sourceCompatibility = it
            targetCompatibility = it
        }
    }

    buildSearchableOptions {
        enabled = false
    }

    wrapper {
        gradleVersion = properties("gradleVersion")
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))
        sinceBuild.set(properties("pluginSinceBuild"))
    }
}