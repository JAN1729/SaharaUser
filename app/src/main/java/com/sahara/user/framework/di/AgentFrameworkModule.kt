package com.sahara.user.framework.di

import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.framework.*
import com.sahara.user.framework.agents.*
import com.sahara.user.framework.tools.AlgorithmTools
import com.sahara.user.framework.tools.SupabaseTools
import com.sahara.user.service.LocationService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

/**
 * Hilt Module for Multi-Agent Framework
 * 
 * Provides:
 * - ToolRegistry with all tools registered
 * - AgentMemory for persistent context
 * - All specialized agents
 * - AgentOrchestrator with everything wired up
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentFrameworkModule {
    
    // ==================== TOOLS ====================
    
    @Provides
    @Singleton
    fun provideSupabaseTools(
        supabaseClient: SupabaseApiClient
    ): SupabaseTools {
        return SupabaseTools(supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideAlgorithmTools(): AlgorithmTools {
        return AlgorithmTools()
    }
    
    @Provides
    @Singleton
    fun provideToolRegistry(
        supabaseTools: SupabaseTools,
        algorithmTools: AlgorithmTools
    ): ToolRegistry {
        val registry = ToolRegistry()
        
        // Register all tools
        runBlocking {
            // Supabase tools
            supabaseTools.getAllTools().forEach { tool ->
                registry.register(tool, "supabase", "data")
            }
            
            // Algorithm tools
            algorithmTools.getAllTools().forEach { tool ->
                registry.register(tool, "algorithm", "compute")
            }
        }
        
        return registry
    }
    
    // ==================== MEMORY ====================
    
    @Provides
    @Singleton
    fun provideAgentMemory(
        supabaseClient: SupabaseApiClient
    ): AgentMemory {
        return AgentMemory(supabaseClient)
    }
    
    // ==================== AGENTS ====================
    
    @Provides
    @Singleton
    fun provideDataAgent(
        toolRegistry: ToolRegistry,
        supabaseTools: SupabaseTools
    ): DataAgent {
        return DataAgent(toolRegistry, supabaseTools)
    }
    
    @Provides
    @Singleton
    fun providePathAgent(
        toolRegistry: ToolRegistry,
        algorithmTools: AlgorithmTools
    ): PathAgent {
        return PathAgent(toolRegistry, algorithmTools)
    }
    
    @Provides
    @Singleton
    fun provideTimeAgent(
        toolRegistry: ToolRegistry,
        algorithmTools: AlgorithmTools
    ): TimeAgent {
        return TimeAgent(toolRegistry, algorithmTools)
    }
    
    @Provides
    @Singleton
    fun provideBusTrackerAgent(
        toolRegistry: ToolRegistry
    ): BusTrackerAgent {
        return BusTrackerAgent(toolRegistry)
    }
    
    @Provides
    @Singleton
    fun provideTransferAgent(
        toolRegistry: ToolRegistry
    ): TransferAgent {
        return TransferAgent(toolRegistry)
    }
    
    // ==================== ORCHESTRATOR ====================
    
    @Provides
    @Singleton
    fun provideAgentOrchestrator(
        toolRegistry: ToolRegistry,
        agentMemory: AgentMemory,
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient,
        locationService: LocationService,
        dataAgent: DataAgent,
        pathAgent: PathAgent,
        timeAgent: TimeAgent,
        busTrackerAgent: BusTrackerAgent,
        transferAgent: TransferAgent
    ): AgentOrchestrator {
        val orchestrator = AgentOrchestrator(
            toolRegistry = toolRegistry,
            agentMemory = agentMemory,
            perplexityClient = perplexityClient,
            supabaseClient = supabaseClient,
            locationService = locationService
        )
        
        // Register all agents
        orchestrator.registerAgents(
            dataAgent,
            pathAgent,
            timeAgent,
            busTrackerAgent,
            transferAgent
        )
        
        return orchestrator
    }
}
