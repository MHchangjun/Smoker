package com.song.cli

import java.io.File

// -------------------- CLI args --------------------
data class Args(
    val project: File,
    val module: String,
    val db: File,
    val task: String?,
    val sarifPath: File?
)

fun parseArgs(raw: Array<String>): Args {
    fun nextValue(i: Int): String {
        if (i + 1 >= raw.size) error("Missing value after ${raw[i]}")
        return raw[i + 1]
    }

    var project = File(".").absoluteFile
    var module = "app"
    var db = File("detekt.sqlite").absoluteFile
    var task: String? = null
    var sarifPath: File? = null

    var i = 0
    while (i < raw.size) {
        when (raw[i]) {
            "--project" -> {
                project = File(nextValue(i)).absoluteFile
                i += 2
            }
            "--module" -> {
                module = nextValue(i)
                i += 2
            }
            "--db" -> {
                db = File(nextValue(i)).absoluteFile
                i += 2
            }
            "--task" -> {
                task = nextValue(i)
                i += 2
            }
            "--sarif" -> {
                sarifPath = File(nextValue(i)).absoluteFile
                i += 2
            }
            "-h", "--help" -> {
                printHelpAndExit()
            }
            else -> error("Unknown arg: ${raw[i]}")
        }
    }

    return Args(
        project = project,
        module = module,
        db = db,
        task = task,
        sarifPath = sarifPath
    )
}

fun printHelpAndExit(): Nothing {
    println(
        """
        smoker usage:
          smoker [--project <path>] [--module app] [--db detekt.sqlite]
                       [--task :app:detekt] [--sarif <sarifFile>]

        Defaults:
          --project = current directory
          --module  = app
          --db      = ./detekt.sqlite
          --task    = :<module>:detekt
          (if --sarif is omitted, it will auto-find latest SARIF under <module>/build/reports/detekt)

        Examples:
          smoker
          smoker --db detekt.sqlite
          smoker --module app --task :app:detekt
          smoker --sarif app/build/reports/detekt/detekt.sarif
        """.trimIndent()
    )
    kotlin.system.exitProcess(0)
}
