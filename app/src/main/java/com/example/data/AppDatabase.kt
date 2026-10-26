package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface V2RayBalancerDao {
    @Query("SELECT * FROM server_profiles ORDER BY id ASC")
    fun observeAllServers(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles ORDER BY id ASC")
    suspend fun getAllServersOnce(): List<ServerProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerProfileEntity>)

    @Query("DELETE FROM server_profiles")
    suspend fun deleteAllServers()

    @Transaction
    suspend fun replaceAllServers(servers: List<ServerProfileEntity>) {
        deleteAllServers()
        insertServers(servers)
    }

    @Update
    suspend fun updateServer(server: ServerProfileEntity)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteServerById(id: Long)

    @Query("UPDATE server_profiles SET inBalancerPool = :inPool WHERE id = :id")
    suspend fun setServerInBalancerPool(id: Long, inPool: Boolean)

    @Query("UPDATE server_profiles SET weight = :weight WHERE id = :id")
    suspend fun updateServerWeight(id: Long, weight: Int)

    @Query("UPDATE server_profiles SET lastLatencyMs = :latencyMs WHERE id = :id")
    suspend fun updateServerLatency(id: Long, latencyMs: Long)

    @Query(
        """
        UPDATE server_profiles 
        SET lastLatencyMs = :latencyMs,
            emaLatencyMs = :emaLatencyMs,
            measuredThroughputKbps = :throughputKbps,
            healthStatus = :healthStatus,
            lastFailureReason = :failureReason,
            weight = :computedWeight,
            successCount = :successCount,
            failureCount = :failureCount,
            consecutiveFailures = :consecutiveFailures,
            consecutiveSuccesses = :consecutiveSuccesses,
            stabilityScore = :stabilityScore,
            lastCheckedAt = :checkedAt,
            lastStatusChangeAt = :statusChangedAt
        WHERE id = :id
        """
    )
    suspend fun updateServerHealthMetrics(
        id: Long,
        latencyMs: Long,
        emaLatencyMs: Long,
        throughputKbps: Long,
        healthStatus: String,
        failureReason: String,
        computedWeight: Int,
        successCount: Int,
        failureCount: Int,
        consecutiveFailures: Int,
        consecutiveSuccesses: Int,
        stabilityScore: Float,
        checkedAt: Long,
        statusChangedAt: Long
    )

    @Query(
        """
        UPDATE server_profiles 
        SET isSimulatedDown = :isDown, 
            lastLatencyMs = :latencyMs, 
            healthStatus = :healthStatus,
            lastFailureReason = :failureReason,
            consecutiveFailures = :consecutiveFailures
        WHERE id = :id
        """
    )
    suspend fun setServerSimulatedDown(
        id: Long,
        isDown: Boolean,
        latencyMs: Long,
        healthStatus: String,
        failureReason: String,
        consecutiveFailures: Int
    )

    @Query("UPDATE server_profiles SET totalBytesUp = totalBytesUp + :deltaUp, totalBytesDown = totalBytesDown + :deltaDown WHERE id = :id")
    suspend fun incrementServerTraffic(id: Long, deltaUp: Long, deltaDown: Long)

    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, id ASC")
    fun observeRoutingRules(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, id ASC")
    suspend fun getRoutingRulesOnce(): List<RoutingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutingRule(rule: RoutingRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutingRules(rules: List<RoutingRuleEntity>)

    @Update
    suspend fun updateRoutingRule(rule: RoutingRuleEntity)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun deleteRoutingRuleById(id: Long)
}

@Database(
    entities = [ServerProfileEntity::class, RoutingRuleEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): V2RayBalancerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "v2ray_balancer.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
