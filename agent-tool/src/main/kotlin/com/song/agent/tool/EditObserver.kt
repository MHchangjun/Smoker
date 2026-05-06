package com.song.agent.tool

fun interface EditObserver {
    fun onEdit(filePath: String, removed: List<String>, added: List<String>)

    companion object {
        val NONE: EditObserver = EditObserver { _, _, _ -> }
    }
}
