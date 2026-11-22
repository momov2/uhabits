/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits

import android.app.Application
import android.content.Context
import android.util.Log
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.setFirstWeekday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.database.UnsupportedDatabaseVersionException
import org.isoron.uhabits.core.reminders.ReminderScheduler
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.sync.FirebaseAuthManager
import org.isoron.uhabits.sync.FirebaseSyncManager
import org.isoron.uhabits.utils.DatabaseUtils
import org.isoron.uhabits.widgets.WidgetUpdater
import java.io.File

/**
 * The Android application for Loop Habit Tracker.
 */
class HabitsApplication : Application() {

    private lateinit var context: Context
    private lateinit var widgetUpdater: WidgetUpdater
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var notificationTray: NotificationTray

    override fun onCreate() {
        super.onCreate()
        context = this

        if (isTestMode()) {
            val db = DatabaseUtils.getDatabaseFile(context)
            if (db.exists()) db.delete()
        }

        try {
            DatabaseUtils.initializeDatabase(context)
        } catch (e: UnsupportedDatabaseVersionException) {
            val db = DatabaseUtils.getDatabaseFile(context)
            db.renameTo(File(db.absolutePath + ".invalid"))
            DatabaseUtils.initializeDatabase(context)
        }

        val db = DatabaseUtils.getDatabaseFile(this)
        HabitsApplication.component = HabitsApplicationComponent::class.create(
            appContext = context,
            dbFile = db
        )

        val prefs = component.preferences
        prefs.lastAppVersion = BuildConfig.VERSION_CODE

        setToday(computeToday(component.preferences.midnightDelayHours, 0))
        setFirstWeekday(prefs.firstWeekday)

        val habitList = component.habitList
        for (h in habitList) h.recompute()

        // Firebase login + cloud sync (branch firebase-login). The managers
        // are plain singletons held here so Settings can reach them without
        // changing the inject graph. Firebase itself is initialised
        // automatically by the google-services plugin. If Firebase fails to
        // initialise (bad config, no Play services, ...), log it and keep
        // running offline instead of crashing startup: local habit tracking
        // does not depend on the cloud.
        try {
            firebaseAuthManager = FirebaseAuthManager(context)
            firebaseSyncManager = FirebaseSyncManager(habitList, component.modelFactory)
            firebaseSyncManager.startAutoSync()
            firebaseSyncManager.syncOnAppStart()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed; continuing offline", e)
        }

        widgetUpdater = component.widgetUpdater.apply {
            startListening()
            scheduleStartDayWidgetUpdate()
        }

        reminderScheduler = component.reminderScheduler
        reminderScheduler.startListening()

        notificationTray = component.notificationTray
        notificationTray.startListening()

        val taskRunner = component.taskRunner
        taskRunner.execute {
            reminderScheduler.scheduleAll()
            widgetUpdater.updateWidgets()
        }
    }

    override fun onTerminate() {
        reminderScheduler.stopListening()
        widgetUpdater.stopListening()
        notificationTray.stopListening()
        // Only set if Firebase init succeeded in onCreate (see the
        // try/catch there); otherwise this would throw
        // UninitializedPropertyAccessException.
        if (::firebaseSyncManager.isInitialized) firebaseSyncManager.stopAutoSync()
        super.onTerminate()
    }

    val component: HabitsApplicationComponent
        get() = HabitsApplication.component

    companion object {
        private const val TAG = "HabitsApplication"

        lateinit var component: HabitsApplicationComponent

        /** Google Sign-In via Firebase Auth (Credential Manager). */
        lateinit var firebaseAuthManager: FirebaseAuthManager

        /** Firestore sync for the local habit database. */
        lateinit var firebaseSyncManager: FirebaseSyncManager

        fun isTestMode(): Boolean {
            return try {
                Class.forName("org.isoron.uhabits.BaseAndroidTest")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }
}
