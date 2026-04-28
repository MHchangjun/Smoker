package com.song.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

class EditorSessionManager(
    private val project: Project,
) {
    fun openForAgent(targetFilePath: String): EditorLease? {
        var lease: EditorLease? = null
        ApplicationManager.getApplication().invokeAndWait {
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(targetFilePath)
            if (vFile == null) {
                log("file not found", targetFilePath)
                return@invokeAndWait
            }

            val fileEditorManager = FileEditorManager.getInstance(project)
            val existingEditors = fileEditorManager.getAllEditors(vFile).toSet()
            log("existing=${existingEditors.size}", vFile.path)
            if (existingEditors.isNotEmpty()) {
                lease = EditorLease(vFile = vFile, openedEditors = emptySet())
                return@invokeAndWait
            }

            val openedEditors = fileEditorManager.openFile(vFile, false).toSet()
            log("opened=${openedEditors.size}", vFile.path)
            lease = EditorLease(vFile = vFile, openedEditors = openedEditors)
        }
        return lease
    }

    fun closeForAgent(lease: EditorLease?) {
        if (lease == null || lease.openedEditors.isEmpty()) return
        ApplicationManager.getApplication().invokeAndWait {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val currentEditors = fileEditorManager.getAllEditors(lease.vFile).toSet()
            val shouldClose = currentEditors.isNotEmpty() && currentEditors.all { it in lease.openedEditors }
            log(
                "closing=${shouldClose}",
                "current=${currentEditors.size}",
                "openedByAgent=${lease.openedEditors.size}",
                lease.vFile.path,
            )
            if (shouldClose) {
                fileEditorManager.closeFile(lease.vFile)
            }
        }
    }

    private fun log(vararg parts: Any?) {
        val msg = parts.filterNotNull().joinToString(" | ") { it.toString() }
        println("[AgentEditor] $msg")
    }
}

data class EditorLease(
    val vFile: VirtualFile,
    val openedEditors: Set<FileEditor>,
)
