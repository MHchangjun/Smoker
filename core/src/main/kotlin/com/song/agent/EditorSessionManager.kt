package com.song.agent

import com.intellij.openapi.application.ApplicationManager
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
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(targetFilePath) ?: return@invokeAndWait

            val fileEditorManager = FileEditorManager.getInstance(project)
            val previousSelection = fileEditorManager.selectedTextEditor?.document?.let {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it)
            }
            val existingEditors = fileEditorManager.getAllEditors(vFile)

            val openedByAgent = existingEditors.isEmpty()
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

            val currentEditors = fileEditorManager.getAllEditors(lease.vFile)
            val shouldClose = lease.openedByAgent && currentEditors.isNotEmpty()

            if (shouldClose) {
                fileEditorManager.closeFile(lease.vFile)
            }
        }
    }
}

data class EditorLease(
    val vFile: VirtualFile,
    val previousSelection: VirtualFile?,
    val openedByAgent: Boolean,
)
