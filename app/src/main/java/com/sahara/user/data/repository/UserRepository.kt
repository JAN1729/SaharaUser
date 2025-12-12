package com.sahara.user.data.repository

import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.data.model.User
import com.sahara.user.data.model.UserTrip
import com.sahara.user.data.model.UserSearchHistory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User Repository
 * 
 * Manages user data including profile, trips, and search history.
 */
@Singleton
class UserRepository @Inject constructor(
    private val supabaseClient: SupabaseApiClient
) {
    
    private var currentUser: User? = null
    
    /**
     * Get or create user by phone number
     */
    suspend fun getOrCreateUser(phone: String): Result<User> {
        val result = supabaseClient.getOrCreateUser(phone)
        result.onSuccess { user ->
            currentUser = user
        }
        return result
    }
    
    /**
     * Get current cached user
     */
    fun getCurrentUser(): User? = currentUser
    
    /**
     * Get user by ID
     */
    suspend fun getUser(userId: String): Result<User> {
        return supabaseClient.getUser(userId)
    }
    
    /**
     * Update user profile
     */
    suspend fun updateUser(user: User): Result<User> {
        val result = supabaseClient.updateUser(user)
        result.onSuccess {
            currentUser = it
        }
        return result
    }
    
    /**
     * Update user preferences
     */
    suspend fun updatePreferences(
        preferredRouteType: String? = null,
        preferredLanguage: String? = null
    ): Result<User> {
        val user = currentUser ?: return Result.failure(Exception("No user logged in"))
        
        val updatedUser = user.copy(
            preferredRouteType = preferredRouteType ?: user.preferredRouteType,
            preferredLanguage = preferredLanguage ?: user.preferredLanguage
        )
        
        return updateUser(updatedUser)
    }
    
    /**
     * Save a trip
     */
    suspend fun saveTrip(trip: UserTrip): Result<UserTrip> {
        return supabaseClient.saveTrip(trip)
    }
    
    /**
     * Get user's trip history
     */
    suspend fun getTripHistory(limit: Int = 20): Result<List<UserTrip>> {
        val user = currentUser ?: return Result.failure(Exception("No user logged in"))
        return supabaseClient.getUserTrips(user.id)
    }
    
    /**
     * Save search history
     */
    suspend fun saveSearch(query: String, origin: String?, destination: String?): Result<Unit> {
        val user = currentUser ?: return Result.failure(Exception("No user logged in"))
        return supabaseClient.saveSearchHistory(user.id, query, origin, destination)
    }
    
    /**
     * Get recent searches
     */
    suspend fun getRecentSearches(limit: Int = 10): Result<List<UserSearchHistory>> {
        val user = currentUser ?: return Result.failure(Exception("No user logged in"))
        return supabaseClient.getSearchHistory(user.id, limit)
    }
    
    /**
     * Logout - clear current user
     */
    fun logout() {
        currentUser = null
    }
}
