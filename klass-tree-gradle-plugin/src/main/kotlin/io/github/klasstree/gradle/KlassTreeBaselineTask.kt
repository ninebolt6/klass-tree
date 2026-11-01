package io.github.klasstree.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class KlassTreeBaselineTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val graphJson: RegularFileProperty

    @get:OutputFile
    abstract val baselineFile: RegularFileProperty

    @TaskAction
    fun writeBaseline() {
        val target = baselineFile.get().asFile
        target.parentFile?.mkdirs()
        graphJson.get().asFile.copyTo(target, overwrite = true)
        logger.lifecycle("klass-tree: baseline written to $target")
    }
}
