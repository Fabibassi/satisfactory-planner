package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameDataLoader
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.TargetRate
import de.fabrikplaner.core.solver.ProductionSolver
import de.fabrikplaner.core.solver.assertClose
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphTest {
    private val game = GameDataLoader.loadResource()

    @Test
    fun generatedMotorGraphIsBalancedAndEditableData() {
        val targets = listOf(TargetRate("motor", 10.0))
        val solved = ProductionSolver(game).solve(targets)
        val graph = GraphLayout.fromSolution(solved, targets, game)
        assertClose(2.0, graph.nodes.single { it.recipeId == "motor" }.machineCount)
        assertTrue(graph.nodes.any { it.kind == NodeKind.SOURCE && it.itemId == "iron_ore" })
        assertTrue(graph.nodes.any { it.kind == NodeKind.SINK && it.itemId == "motor" })
        assertTrue(graph.nodes.all { it.x >= GraphMetrics.ORIGIN - 0.1f })
        val report = GraphBalancer.evaluate(graph.nodes, graph.edges, game)
        for (node in graph.nodes.filter { it.kind == NodeKind.RECIPE || it.kind == NodeKind.SINK }) {
            val balance = report.nodes.getValue(node.id)
            for (input in balance.inputs) {
                assertEquals(
                    FlowStatus.BALANCED,
                    input.status,
                    "${node.id} ${input.itemId} supplied ${input.actualPerMinute} required ${input.expectedPerMinute}",
                )
            }
        }
    }

    @Test
    fun underfedSmelterShowsUnderproduction() {
        val nodes = listOf(
            source("ore-src", "iron_ore", 15.0, 0f),
            recipe("smelter-1", "iron_ingot", 1.0, 300f),
            sink("ingot-sink", "iron_ingot", 30.0, 600f),
        )
        val edges = listOf(
            GraphEdge("e1", "ore-src", "smelter-1", "iron_ore"),
            GraphEdge("e2", "smelter-1", "ingot-sink", "iron_ingot"),
        )
        val report = GraphBalancer.evaluate(nodes, edges, game)
        val smelterIn = report.nodes.getValue("smelter-1").inputs.single()
        assertEquals(FlowStatus.UNDER, smelterIn.status)
        assertClose(15.0, smelterIn.actualPerMinute)
        assertClose(30.0, smelterIn.expectedPerMinute)
        val sink = report.nodes.getValue("ingot-sink").inputs.single()
        assertEquals(FlowStatus.UNDER, sink.status)
        assertClose(15.0, sink.actualPerMinute)
        assertClose(0.5, report.nodes.getValue("smelter-1").runRatio, 1e-3)
    }

    @Test
    fun surplusOreShowsOverOnTheMachine() {
        val nodes = listOf(
            source("ore-src", "iron_ore", 60.0, 0f),
            recipe("smelter-1", "iron_ingot", 1.0, 300f),
        )
        val edges = listOf(GraphEdge("e1", "ore-src", "smelter-1", "iron_ore"))
        val report = GraphBalancer.evaluate(nodes, edges, game)
        assertEquals(FlowStatus.OVER, report.nodes.getValue("smelter-1").inputs.single().status)
        assertEquals(FlowStatus.UNCONNECTED, report.nodes.getValue("smelter-1").outputs.single().status)
        assertClose(30.0, report.nodes.getValue("smelter-1").outputs.single().actualPerMinute)
    }

    @Test
    fun splitterFollowsDownstreamDemand() {
        val nodes = listOf(
            source("src", "iron_ore", 30.0, 0f),
            GraphNode("split", NodeKind.SPLITTER, 200f, 0f, itemId = "iron_ore"),
            sink("a", "iron_ore", 10.0, 400f, y = 0f),
            sink("b", "iron_ore", 20.0, 400f, y = 200f),
        )
        val edges = listOf(
            GraphEdge("e1", "src", "split", "iron_ore"),
            GraphEdge("e2", "split", "a", "iron_ore"),
            GraphEdge("e3", "split", "b", "iron_ore"),
        )
        val report = GraphBalancer.evaluate(nodes, edges, game)
        assertClose(10.0, report.nodes.getValue("a").inputs.single().actualPerMinute)
        assertClose(20.0, report.nodes.getValue("b").inputs.single().actualPerMinute)
        assertEquals(FlowStatus.BALANCED, report.nodes.getValue("a").inputs.single().status)
        assertEquals(FlowStatus.BALANCED, report.nodes.getValue("b").inputs.single().status)
    }

    @Test
    fun halfClockMatchesHalfOre() {
        val nodes = listOf(
            source("ore-src", "iron_ore", 15.0, 0f),
            recipe("smelter-1", "iron_ingot", 1.0, 300f).copy(clockPercent = 50.0),
            sink("ingot-sink", "iron_ingot", 15.0, 600f),
        )
        val edges = listOf(
            GraphEdge("e1", "ore-src", "smelter-1", "iron_ore"),
            GraphEdge("e2", "smelter-1", "ingot-sink", "iron_ingot"),
        )
        val report = GraphBalancer.evaluate(nodes, edges, game)
        assertEquals(FlowStatus.BALANCED, report.nodes.getValue("smelter-1").inputs.single().status)
        assertEquals(FlowStatus.BALANCED, report.nodes.getValue("ingot-sink").inputs.single().status)
        assertClose(15.0, report.nodes.getValue("ingot-sink").inputs.single().actualPerMinute)
    }

    @Test
    fun rejectsMismatchedConnection() {
        val nodes = listOf(
            source("ore-src", "iron_ore", 30.0, 0f),
            recipe("wire-1", "wire", 1.0, 300f),
        )
        val result = GraphOps.connect(nodes, emptyList(), "ore-src", "wire-1", "iron_ore", game, "e")
        assertFalse(result.connected)
        val ok = GraphOps.connect(
            listOf(source("ore-src", "iron_ore", 30.0, 0f), recipe("smelter-1", "iron_ingot", 1.0, 300f)),
            emptyList(),
            "ore-src",
            "smelter-1",
            "iron_ore",
            game,
            "e",
        )
        assertTrue(ok.connected)
        assertEquals(1, ok.edges.size)
    }

    @Test
    fun cameraRoundTripAndPorts() {
        val camera = Camera(0.5f, 20f, 40f)
        val (x, y) = CameraMath.screenToWorld(120f, 90f, camera, density = 2f)
        val (sx, sy) = CameraMath.worldToScreen(x, y, camera, density = 2f)
        assertTrue(abs(sx - 120f) < 0.01f)
        assertTrue(abs(sy - 90f) < 0.01f)
        val node = recipe("n", "iron_ingot", 1.0, 10f)
        val port = GraphMetrics.outputPort(node, 0, 1)
        assertEquals(node.x + GraphMetrics.CARD_W, port.first)
        assertEquals(node.y + GraphMetrics.portY(0, 1), port.second)
        val input = GraphMetrics.inputPort(node, 0, 1)
        assertEquals(node.x, input.first)
        assertTrue(port.second > node.y)
        assertTrue(port.second < node.y + GraphMetrics.CARD_H)
    }

    @Test
    fun barycenterRemovesTheCrossing() {
        val nodes = listOf(
            GraphNode("A", NodeKind.SOURCE, 0f, 0f, itemId = "iron_ore"),
            GraphNode("B", NodeKind.SOURCE, 0f, 0f, itemId = "copper_ore"),
            GraphNode("C", NodeKind.SINK, 0f, 0f, itemId = "copper_ore"),
            GraphNode("D", NodeKind.SINK, 0f, 0f, itemId = "iron_ore"),
        )
        val edges = listOf(
            GraphEdge("e1", "A", "D", "iron_ore"),
            GraphEdge("e2", "B", "C", "copper_ore"),
        )
        val placed = GraphLayout.arrange(nodes, edges)
        assertEquals(0, GraphLayout.straightCrossings(placed, edges))
        val byId = placed.associateBy { it.id }
        assertTrue(byId.getValue("A").x < byId.getValue("D").x)
        assertTrue(byId.getValue("D").y < byId.getValue("C").y)
    }

    @Test
    fun motorChainFlowsLeftToRightWithFewerCrossingsThanIdOrder() {
        val targets = listOf(TargetRate("motor", 10.0))
        val solved = ProductionSolver(game).solve(targets)
        val graph = GraphLayout.fromSolution(solved, targets, game)
        val byId = graph.nodes.associateBy { it.id }
        for (edge in graph.edges) {
            assertTrue(
                byId.getValue(edge.fromNodeId).x < byId.getValue(edge.toNodeId).x,
                "${edge.fromNodeId} -> ${edge.toNodeId}",
            )
        }
        val sugiyama = GraphLayout.straightCrossings(graph.nodes, graph.edges)
        val idOrdered = idOrderWithinLayers(graph.nodes)
        val alphabetical = GraphLayout.straightCrossings(idOrdered, graph.edges)
        assertTrue(sugiyama <= alphabetical, "sugiyama=$sugiyama alphabetical=$alphabetical")
        assertTrue(sugiyama <= 4, "crossings=$sugiyama")
        val sameLayer = graph.nodes.groupBy { it.x }
        for (column in sameLayer.values) {
            val sorted = column.sortedBy { it.y }
            for (index in 1 until sorted.size) {
                assertTrue(sorted[index].y >= sorted[index - 1].y + GraphMetrics.CARD_H - 0.1f)
            }
        }
    }

    @Test
    fun portsPointAtTheConnectedNeighbor() {
        val plate = recipe("rip", "reinforced_iron_plate", 1.0, 400f).copy(y = 100f)
        val screws = recipe("scr", "screw", 1.0, 0f).copy(y = 0f)
        val plates = recipe("plt", "iron_plate", 1.0, 0f).copy(y = 300f)
        val nodes = listOf(plate, screws, plates)
        val edges = listOf(
            GraphEdge("e1", "scr", "rip", "screw"),
            GraphEdge("e2", "plt", "rip", "iron_plate"),
        )
        assertEquals(listOf("screw", "iron_plate"), GraphPorts.orderedInputs(plate, game, nodes, edges))
        val screwPort = GraphPorts.inputCenter(plate, "screw", game, nodes, edges)
        val platePort = GraphPorts.inputCenter(plate, "iron_plate", game, nodes, edges)
        assertEquals(plate.x, screwPort.first)
        assertTrue(screwPort.second < platePort.second)
    }

    private fun idOrderWithinLayers(nodes: List<GraphNode>): List<GraphNode> {
        val layers = nodes.groupBy { it.x }.toSortedMap()
        val placed = mutableListOf<GraphNode>()
        for ((x, column) in layers) {
            column.sortedBy { it.id }.forEachIndexed { index, node ->
                val y = GraphMetrics.ORIGIN + index * (GraphMetrics.CARD_H + GraphMetrics.GAP_Y)
                placed += node.copy(x = x, y = y)
            }
        }
        return placed
    }

    private fun source(id: String, itemId: String, rate: Double, x: Float) =
        GraphNode(id, NodeKind.SOURCE, x, 0f, itemId = itemId, ratePerMinute = rate)

    private fun sink(id: String, itemId: String, rate: Double, x: Float, y: Float = 0f) =
        GraphNode(id, NodeKind.SINK, x, y, itemId = itemId, ratePerMinute = rate)

    private fun recipe(id: String, recipeId: String, machines: Double, x: Float) =
        GraphNode(
            id = id,
            kind = NodeKind.RECIPE,
            x = x,
            y = 0f,
            recipeId = recipeId,
            machineCount = machines,
            clockPercent = 100.0,
        )
}
