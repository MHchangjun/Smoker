package com.song.screen.host

import com.song.screen.HostVia

/**
 * One hosting edge from a host class to a child class, as discovered by a scanner.
 * Carries the via reason and the source XML file where the edge was declared,
 * which the [LayerTreeBuilder][com.song.screen.tree.LayerTreeBuilder] copies
 * into the output [com.song.screen.HostEdge].
 */
data class HostedChild(
    val childFqn: String,
    val via: HostVia,
    val sourceFile: String,
)
