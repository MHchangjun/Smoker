package com.song.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
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
            val previousSelection = fileEditorManager.selectedTextEditor?.document?.let {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it)
            }
            val existingEditors = fileEditorManager.getAllEditors(vFile)
            log("existing=${existingEditors.size}", vFile.path, "previous=${previousSelection?.path}")

            val selectedEditor = fileEditorManager.openTextEditor(OpenFileDescriptor(project, vFile), true)
            val selectedFile = selectedEditor?.document?.let {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it)
            }
            val openedByAgent = existingEditors.isEmpty()
            log(
                "selected=${selectedEditor != null}",
                "selectedFile=${selectedFile?.path}",
                "openedByAgent=$openedByAgent",
                vFile.path,
            )
            lease = EditorLease(
                vFile = vFile,
                previousSelection = previousSelection,
                openedByAgent = openedByAgent,
            )
        }
        return lease
    }

    fun closeForAgent(lease: EditorLease?) {
        if (lease == null) return
        ApplicationManager.getApplication().invokeAndWait {
            val fileEditorManager = FileEditorManager.getInstance(project)
            lease.previousSelection?.let { previousFile ->
                val restored = fileEditorManager.openTextEditor(OpenFileDescriptor(project, previousFile), true) != null
                log("restored=$restored", "previous=${previousFile.path}")
            }

            val currentEditors = fileEditorManager.getAllEditors(lease.vFile)
            val shouldClose = lease.openedByAgent && currentEditors.isNotEmpty()
            log(
                "closing=${shouldClose}",
                "current=${currentEditors.size}",
                "openedByAgent=${lease.openedByAgent}",
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
    val previousSelection: VirtualFile?,
    val openedByAgent: Boolean,
)
