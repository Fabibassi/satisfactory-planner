package de.fabrikplaner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TargetRate(
    val itemId: String,
    val perMinute: Double,
)

@Serializable
enum class NodeKind {
    @SerialName("recipe") RECIPE,
    @SerialName("source") SOURCE,
    @SerialName("sink") SINK,
    @SerialName("splitter") SPLITTER,
    @SerialName("merger") MERGER,
}

@Serializable
data class GraphNode(
    val id: String,
    val kind: NodeKind,
    val x: Float,
    val y: Float,
    val recipeId: String? = null,
    val buildingId: String? = null,
    val itemId: String? = null,
    val machineCount: Double = 1.0,
    val clockPercent: Double = 100.0,
    val ratePerMinute: Double = 0.0,
)

@Serializable
data class GraphEdge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val itemId: String,
)

@Serializable
data class SavedPlan(
    val id: String,
    val name: String,
    val targets: List<TargetRate> = emptyList(),
    val recipeSelection: Map<String, String> = emptyMap(),
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    /** Signature of targets + recipe selection at the time the graph was generated. */
    val graphSignature: String = "",
    val updatedAtEpochMs: Long = 0L,
    val schema: Int = 1,
) {
    fun inputSignature(): String {
        val targetPart = targets.sortedBy { it.itemId }.joinToString(";") { "${it.itemId}=${it.perMinute}" }
        val selectionPart = recipeSelection.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
        return "$targetPart|$selectionPart"
    }

    fun graphIsStale(): Boolean = nodes.isNotEmpty() && graphSignature != inputSignature()
}
