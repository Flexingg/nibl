package com.bytelog.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneId

/** The Health Connect calls Nibl makes, behind an interface so the sync can be tested without the platform. */
interface HealthStore {
    suspend fun readNutrition(from: Instant, to: Instant): List<ExistingRecord>
    suspend fun readHydration(from: Instant, to: Instant): List<ExistingRecord>
    /** Writes the records and returns Health Connect's ids for them. */
    suspend fun writeFood(records: List<FoodRecord>, version: Long, zone: ZoneId): List<String>
    suspend fun writeWater(records: List<WaterRecord>, version: Long, zone: ZoneId): List<String>
    suspend fun deleteFood(clientRecordIds: List<String>)
    suspend fun deleteWater(clientRecordIds: List<String>)
}

/** Health Connect on the phone. */
class HealthConnectStore(context: Context) : HealthStore {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class)
        )

        fun isAvailable(context: Context) = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        suspend fun hasPermissions(context: Context): Boolean =
            isAvailable(context) && HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

        private fun mealType(name: String) = when (name.trim().lowercase()) {
            "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
            "lunch" -> MealType.MEAL_TYPE_LUNCH
            "dinner" -> MealType.MEAL_TYPE_DINNER
            "snack", "snacks" -> MealType.MEAL_TYPE_SNACK
            else -> MealType.MEAL_TYPE_UNKNOWN
        }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(type: kotlin.reflect.KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val res = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageToken = token))
            out += res.records
            token = res.pageToken
        } while (token != null)
        return out
    }

    override suspend fun readNutrition(from: Instant, to: Instant) = readAll(NutritionRecord::class, from, to).map {
        ExistingRecord(it.metadata.clientRecordId, it.metadata.dataOrigin.packageName, it.name, it.energy?.inKilocalories, start = it.startTime)
    }

    override suspend fun readHydration(from: Instant, to: Instant) = readAll(HydrationRecord::class, from, to).map {
        ExistingRecord(it.metadata.clientRecordId, it.metadata.dataOrigin.packageName, ml = it.volume.inMilliliters, start = it.startTime)
    }

    private fun mass(g: Float) = g.takeIf { it > 0f }?.let { Mass.grams(it.toDouble()) }

    override suspend fun writeFood(records: List<FoodRecord>, version: Long, zone: ZoneId): List<String> {
        if (records.isEmpty()) return emptyList()
        val hc = records.map { r ->
            val offset = zone.rules.getOffset(r.start)
            NutritionRecord(
                startTime = r.start, startZoneOffset = offset,
                endTime = r.start.plusSeconds(60), endZoneOffset = offset,
                metadata = Metadata.manualEntry(clientRecordId = r.clientRecordId, clientRecordVersion = version),
                energy = r.entry.calories.takeIf { it > 0f }?.let { Energy.kilocalories(it.toDouble()) },
                protein = mass(r.entry.protein), totalCarbohydrate = mass(r.entry.carbs), totalFat = mass(r.entry.fat),
                name = r.entry.foodName.take(1000),
                mealType = mealType(r.entry.mealName)
            )
        }
        return client.insertRecords(hc).recordIdsList
    }

    override suspend fun writeWater(records: List<WaterRecord>, version: Long, zone: ZoneId): List<String> {
        if (records.isEmpty()) return emptyList()
        val hc = records.map { r ->
            val offset = zone.rules.getOffset(r.start)
            HydrationRecord(
                startTime = r.start, startZoneOffset = offset,
                endTime = r.start.plusSeconds(60), endZoneOffset = offset,
                volume = Volume.milliliters(r.log.waterMl.toDouble()),
                metadata = Metadata.manualEntry(clientRecordId = r.clientRecordId, clientRecordVersion = version)
            )
        }
        return client.insertRecords(hc).recordIdsList
    }

    override suspend fun deleteFood(clientRecordIds: List<String>) {
        if (clientRecordIds.isNotEmpty()) client.deleteRecords(NutritionRecord::class, emptyList(), clientRecordIds)
    }

    override suspend fun deleteWater(clientRecordIds: List<String>) {
        if (clientRecordIds.isNotEmpty()) client.deleteRecords(HydrationRecord::class, emptyList(), clientRecordIds)
    }
}
