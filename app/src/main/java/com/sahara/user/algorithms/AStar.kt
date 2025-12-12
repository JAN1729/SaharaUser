package com.sahara.user.algorithms

import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * A* (A-Star) Pathfinding Algorithm
 * 
 * An extension of Dijkstra that uses a heuristic function
 * to guide the search towards the goal more efficiently.
 * 
 * This is a pre-coded algorithm - NO machine learning.
 */
object AStar {
    
    /**
     * Node with position for heuristic calculation
     */
    data class GeoNode(
        val id: String,
        val lat: Double,
        val lng: Double,
        val data: Map<String, Any> = emptyMap()
    )
    
    /**
     * Edge with weight
     */
    data class Edge(
        val from: String,
        val to: String,
        val weight: Double
    )
    
    /**
     * Graph with geographic nodes
     */
    data class GeoGraph(
        val nodes: List<GeoNode>,
        val edges: List<Edge>
    ) {
        val nodeMap: Map<String, GeoNode> by lazy {
            nodes.associateBy { it.id }
        }
        
        val adjacencyList: Map<String, List<Pair<String, Double>>> by lazy {
            edges.groupBy { it.from }
                .mapValues { (_, edges) -> edges.map { it.to to it.weight } }
        }
    }
    
    /**
     * Path result
     */
    data class PathResult(
        val path: List<String>,
        val totalCost: Double,
        val nodesExplored: Int,
        val found: Boolean
    )
    
    /**
     * Find shortest path using A* algorithm
     * 
     * Uses Haversine distance as heuristic for geographic data
     * 
     * @param graph The graph to search
     * @param start Starting node ID
     * @param goal Goal node ID
     * @return PathResult
     */
    fun findPath(graph: GeoGraph, start: String, goal: String): PathResult {
        val startNode = graph.nodeMap[start] ?: return PathResult(emptyList(), 0.0, 0, false)
        val goalNode = graph.nodeMap[goal] ?: return PathResult(emptyList(), 0.0, 0, false)
        
        // f(n) = g(n) + h(n)
        // g(n) = cost from start to n
        // h(n) = heuristic estimate from n to goal
        
        // Priority queue: (f_score, nodeId)
        val openSet = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        
        // g_score: cost from start to node
        val gScore = mutableMapOf<String, Double>()
        
        // f_score: g_score + heuristic
        val fScore = mutableMapOf<String, Double>()
        
        // Track path
        val cameFrom = mutableMapOf<String, String>()
        
        // Track explored nodes
        var nodesExplored = 0
        
        // Initialize
        gScore[start] = 0.0
        fScore[start] = heuristic(startNode, goalNode)
        openSet.add(fScore[start]!! to start)
        
        val closedSet = mutableSetOf<String>()
        
        while (openSet.isNotEmpty()) {
            val (_, current) = openSet.poll()
            nodesExplored++
            
            // Found goal
            if (current == goal) {
                return PathResult(
                    path = reconstructPath(cameFrom, current),
                    totalCost = gScore[current] ?: 0.0,
                    nodesExplored = nodesExplored,
                    found = true
                )
            }
            
            // Skip if already processed
            if (current in closedSet) continue
            closedSet.add(current)
            
            // Get current node data
            val currentNode = graph.nodeMap[current] ?: continue
            
            // Explore neighbors
            val neighbors = graph.adjacencyList[current] ?: emptyList()
            for ((neighbor, weight) in neighbors) {
                if (neighbor in closedSet) continue
                
                val neighborNode = graph.nodeMap[neighbor] ?: continue
                
                val tentativeG = (gScore[current] ?: Double.MAX_VALUE) + weight
                
                if (tentativeG < (gScore[neighbor] ?: Double.MAX_VALUE)) {
                    cameFrom[neighbor] = current
                    gScore[neighbor] = tentativeG
                    fScore[neighbor] = tentativeG + heuristic(neighborNode, goalNode)
                    openSet.add(fScore[neighbor]!! to neighbor)
                }
            }
        }
        
        // No path found
        return PathResult(
            path = emptyList(),
            totalCost = Double.MAX_VALUE,
            nodesExplored = nodesExplored,
            found = false
        )
    }
    
    /**
     * Heuristic function using Haversine distance
     */
    private fun heuristic(from: GeoNode, to: GeoNode): Double {
        return Haversine.distance(from.lat, from.lng, to.lat, to.lng)
    }
    
    /**
     * Reconstruct path from cameFrom map
     */
    private fun reconstructPath(cameFrom: Map<String, String>, current: String): List<String> {
        val path = mutableListOf(current)
        var node = current
        
        while (cameFrom.containsKey(node)) {
            node = cameFrom[node]!!
            path.add(0, node)
        }
        
        return path
    }
}
