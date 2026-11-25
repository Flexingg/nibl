package com.bytelog.app.health

import android.content.Context

/**
 * The SparkyFitness phone app. When it's on the phone it already does both Health Connect jobs for the Sparky
 * diary: it writes the diary (including food and drinks logged in Nibl, once they're on Sparky) to Health Connect,
 * and it sends Health Connect's steps, sleep, workouts and so on to Sparky. If Nibl did them too, Sparky would
 * import Nibl's food back as second entries and store sleep and workouts twice.
 */
object SparkyFitnessApp {
    fun installed(context: Context): Boolean = HealthConnectPlan.SPARKY_PACKAGES.any { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }
}
