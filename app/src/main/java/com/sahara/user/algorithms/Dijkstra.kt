package com.sahara.user.algorithms

import java.util.PriorityQueue

/**
 * Dijkstra's Shortest Path Algorithm
 * 
 * Finds the shortest path between nodes in a weighted graph.
 * Used for route optimization.
 * 
 * This is a pre-coded algorithm - NO machine learning.
 */
object Dijkstra {
    
    /**
     * Graph node with ID and connections
     */
    data class Node(
        val id: String,
        val data: Map<String, Any> = emptyMap()
    )
    
    /**
     * Edge connecting two nodes with a weight (distance/time)
     */
    data class Edge(
        val from: String,
        val to: String,
        val weight: Double
    )
    
    /**
     * Graph structure
     */
    data class Graph(
        val nodes: List<Node>,
        val edges: List<Edge>
    ) {
        // Adjacency list representation
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
        val totalWeight: Double,
        val found: Boolean
    )
    
    /**
     * Find shortest path between start and end nodes
     * 
     * @param graph The graph to search
     * @param start Starting node ID
     * @param end Ending node ID
     * @return PathResult with path and total weight
     */
    fun findShortestPath(graph: Graph, start: String, end: String): PathResult {
        // Priority queue: (distance, nodeId)
        val pq = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        
        // Distance map: nodeId -> shortest distance from start
        val distances = mutableMapOf<String, Double>()
        
        // Previous node map: nodeId -> previous node in shortest path
        val previous = mutableMapOf<String, String?>()
        
        // Initialize all distances to infinity
        graph.nodes.forEach { node ->
            distances[node.id] = Double.MAX_VALUE
            previous[node.id] = null
        }
        
        // Start node distance is 0
        distances[start] = 0.0
        pq.add(0.0 to start)
        
        while (pq.isNotEmpty()) {
            val (currentDist, current) = pq.poll()
            
            // Found destination
            if (current == end) {
                return PathResult(
                    path = reconstructPath(previous, start, end),
                    totalWeight = distances[end] ?: Double.MAX_VALUE,
                    found = true
                )
            }
            
            // Skip if we've found a better path already
            if (currentDist > (distances[current] ?: Double.MAX_VALUE)) {
                continue
            }
            
            // Check all neighbors
            val neighbors = graph.adjacencyList[current] ?: emptyList()
            for ((neighbor, weight) in neighbors) {
                val newDist = currentDist + weight
                
                if (newDist < (distances[neighbor] ?: Double.MAX_VALUE)) {
                    distances[neighbor] = newDist
                    previous[neighbor] = current
                    pq.add(newDist to neighbor)
                }
            }
        }
        
        // No path found
        return PathResult(
            path = emptyList(),
            totalWeight = Double.MAX_VALUE,
            found = false
        )
    }
    
    /**
     * Reconstruct path from previous map
     */
    private fun reconstructPath(
        previous: Map<String, String?>,
        start: String,
        end: String
    ): List<String> {
        val path = mutableListOf<String>()
        var current: String? = end
        
        while (current != null) {
            path.add(current)
            if (current == start) break
            current = previous[current]
        }
        
        return path.reversed()
    }
    
    /**
     * Find all shortest paths from a start node
     * 
     * @param graph The graph to search
     * @param start Starting node ID
     * @return Map of nodeId to (distance, path)
     */
    fun findAllShortestPaths(graph: Graph, start: String): Map<String, PathResult> {
        val results = mutableMapOf<String, PathResult>()
        
        graph.nodes.forEach { node ->
            if (node.id != start) {
                results[node.id] = findShortestPath(graph, start, node.id)
            }
        }
        
        return results
    }
}
