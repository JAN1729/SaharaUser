package com.sahara.user.di

import android.content.Context
import com.sahara.user.agents.*
import com.sahara.user.data.api.AgentApiClient
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.repository.AgentRepository
import com.sahara.user.data.repository.RouteRepository
import com.sahara.user.data.repository.UserRepository
import com.sahara.user.service.LocationService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Dependency Injection Module
 * 
 * ARCHITECTURE:
 * - Algorithms do all calculations
 * - Agents analyze and communicate
 * - MultiAgentCoordinator orchestrates everything
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    // ==================== API CLIENTS ====================
    
    @Provides
    @Singleton
    fun providePerplexityClient(): PerplexityClient {
        return PerplexityClient()
    }
    
    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseApiClient {
        return SupabaseApiClient()
    }
    
    @Provides
    @Singleton
    fun provideAgentApiClient(): AgentApiClient {
        return AgentApiClient()
    }
    
    @Provides
    @Singleton
    fun provideAgentRepository(
        agentApiClient: AgentApiClient,
        locationService: LocationService
    ): AgentRepository {
        return AgentRepository(agentApiClient, locationService)
    }
    
    // ==================== SERVICES ====================
    
    @Provides
    @Singleton
    fun provideLocationService(
        @ApplicationContext context: Context
    ): LocationService {
        return LocationService(context)
    }
    
    // ==================== REPOSITORIES ====================
    
    @Provides
    @Singleton
    fun provideRouteRepository(supabaseClient: SupabaseApiClient): RouteRepository {
        return RouteRepository(supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideUserRepository(supabaseClient: SupabaseApiClient): UserRepository {
        return UserRepository(supabaseClient)
    }
    
    // ==================== COMMUNICATION HUB ====================
    
    @Provides
    @Singleton
    fun provideAgentCommunicationHub(): AgentCommunicationHub {
        return AgentCommunicationHub()
    }
    
    // ==================== AI AGENTS ====================
    
    @Provides
    @Singleton
    fun provideSQLAgent(
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient
    ): SQLAgent {
        return SQLAgent(perplexityClient, supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideAlgorithmAgent(
        perplexityClient: PerplexityClient
    ): AlgorithmAgent {
        return AlgorithmAgent(perplexityClient)
    }

    @Provides
    @Singleton
    fun providePathAgent(
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient
    ): PathAgent {
        return PathAgent(perplexityClient, supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideTimeAgent(
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient
    ): TimeAgent {
        return TimeAgent(perplexityClient, supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideBusTrackerAgent(
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient
    ): BusTrackerAgent {
        return BusTrackerAgent(perplexityClient, supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideUserContextAgent(
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient,
        locationService: LocationService
    ): UserContextAgent {
        return UserContextAgent(perplexityClient, supabaseClient, locationService)
    }
    
    @Provides
    @Singleton
    fun provideTransferAgent(
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient
    ): TransferAgent {
        return TransferAgent(perplexityClient, supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideAnalyticsAgent(
        perplexityClient: PerplexityClient,
        supabaseClient: SupabaseApiClient
    ): AnalyticsAgent {
        return AnalyticsAgent(perplexityClient, supabaseClient)
    }
    
    @Provides
    @Singleton
    fun provideCommunicationAgent(
        perplexityClient: PerplexityClient
    ): CommunicationAgent {
        return CommunicationAgent(perplexityClient)
    }
    
    // ==================== MULTI-AGENT COORDINATOR ====================
    
    @Provides
    @Singleton
    fun provideMultiAgentCoordinator(
        communicationHub: AgentCommunicationHub,
        supabaseClient: SupabaseApiClient,
        locationService: LocationService,
        sqlAgent: SQLAgent,
        algorithmAgent: AlgorithmAgent,
        pathAgent: PathAgent,
        timeAgent: TimeAgent,
        busTrackerAgent: BusTrackerAgent,
        transferAgent: TransferAgent,
        userContextAgent: UserContextAgent,
        analyticsAgent: AnalyticsAgent
    ): MultiAgentCoordinator {
        return MultiAgentCoordinator(
            communicationHub = communicationHub,
            supabaseClient = supabaseClient,
            locationService = locationService,
            dataAgent = sqlAgent,
            algorithmAgent = algorithmAgent,
            pathAgent = pathAgent,
            timeAgent = timeAgent,
            busTrackerAgent = busTrackerAgent,
            transferAgent = transferAgent,
            userContextAgent = userContextAgent,
            analyticsAgent = analyticsAgent
        )
    }
    
    // ==================== MAIN ORCHESTRATOR (Legacy) ====================
    // Kept for backward compatibility, uses MultiAgentCoordinator internally
    
    @Provides
    @Singleton
    fun provideMainOrchestrator(
        perplexityClient: PerplexityClient,
        communicationHub: AgentCommunicationHub,
        supabaseClient: SupabaseApiClient,
        locationService: LocationService
    ): MainOrchestrator {
        return MainOrchestrator(
            perplexityClient = perplexityClient,
            communicationHub = communicationHub,
            supabaseClient = supabaseClient,
            locationService = locationService
        )
    }
}
