/*
 * Copyright (C) 2026 Loop Habit Tracker contributors.
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without any implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.sync

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList

/**
 * Firestore sync for the local habit database (v1).
 *
 * Data layout (one document per habit, entries embedded in the document):
 * ```
 * users/{uid}/habits/{habitUuid}
 *   name, description, question, type, color, frequencyNumerator,
 *   frequencyDenominator, targetType, targetValue, unit, isArchived,
 *   position, reminderHour, reminderMinute, reminderDays, updatedAt,
 *   entries: { "<unixMillis>": { "value": Int, "notes": String } }
 * ```
 * One document per habit keeps the code simple and robust; a habit with
 * five years of daily entries is still far below Firestore's 1 MiB
 * document limit. Scores and streaks are not synced: they are recomputed
 * locally from the entries after every download.
 *
 * Merge rules (deliberately simple, documented):
 * - A habit that exists only in the cloud is created locally.
 * - An entry that exists only in the cloud is added locally.
 * - If the same entry exists on both sides with different values, the
 *   LOCAL value wins (there is no per-entry modification timestamp to
 *   decide otherwise). Habit fields (name, color, ...) are likewise never
 *   overwritten by a download; only missing habits/entries are imported.
 * - Upload always writes the full local state, so after a sync both sides
 *   hold the merged state.
 *
 * Safety: sync never deletes local data. A failed upload leaves the
 * local database exactly as it was, and the failure is reported to the
 * caller instead of being swallowed. A failed download is logged and
 * treated as "nothing downloaded", so the upload of the merged local
 * state still runs. Firestore's offline persistence (enabled by default
 * on Android) queues writes while the device is offline and sends them
 * when connectivity returns.
 */
class FirebaseSyncManager(
    private val habitList: HabitList,
    private val modelFactory: ModelFactory
) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while remote data is being applied locally; the auto-upload
     * listener ignores model notifications raised in that window. */
    @Volatile
    private var isApplyingRemote = false

    private var autoUploadJob: Job? = null
    private var autoSyncStarted = false

    private val changeListener = ModelObservable.Listener {
        if (isApplyingRemote) return@Listener
        scheduleAutoUpload()
    }

    data class SyncStats(val uploaded: Int, val downloaded: Int, val created: Int)

    fun isSignedIn(): Boolean = auth.currentUser != null

    fun currentUid(): String? = auth.currentUser?.uid

    /**
     * Starts listening to local habit changes and uploading them (debounced
     * by [AUTO_UPLOAD_DELAY_MS]) whenever the user is signed in. Safe to
     * call more than once.
     */
    fun startAutoSync() {
        if (autoSyncStarted) return
        autoSyncStarted = true
        habitList.observable.addListener(changeListener)
    }

    fun stopAutoSync() {
        if (!autoSyncStarted) return
        autoSyncStarted = false
        habitList.observable.removeListener(changeListener)
        autoUploadJob?.cancel()
    }

    /**
     * Best-effort sync when the app starts: if the user is already signed
     * in, download remote changes first, then upload the merged state.
     * Failures are only logged; the app must keep working offline.
     */
    fun syncOnAppStart() {
        scope.launch {
            try {
                syncNow()
            } catch (e: Exception) {
                Log.w(TAG, "Background sync on app start failed", e)
            }
        }
    }

    /**
     * Full sync: download and merge the cloud state into the local
     * database, then upload the merged local state. Must be called from a
     * coroutine; does the work on Dispatchers.IO.
     */
    suspend fun syncNow(): SyncStats = withContext(Dispatchers.IO) {
        // Never hang silently: without a timeout a stuck network call
        // leaves the user with no feedback at all. 60s is generous for
        // 19 small documents; a timeout surfaces as a "Sync failed" toast.
        withTimeout(SYNC_TIMEOUT_MS) {
            val uid = currentUid()
                ?: throw IllegalStateException("Not signed in. Sign in with Google first.")
            val downloaded = downloadAndMerge(uid)
            val uploaded = uploadAllHabits(uid)
            SyncStats(uploaded = uploaded, downloaded = downloaded.habits, created = downloaded.created)
        }
    }

    /**
     * Uploads every local habit to Firestore. Returns the number of habits
     * uploaded. Throws on failure; local data is untouched either way.
     */
    suspend fun uploadAllHabits(): Int = withContext(Dispatchers.IO) {
        val uid = currentUid()
            ?: throw IllegalStateException("Not signed in. Sign in with Google first.")
        uploadAllHabits(uid)
    }

    private suspend fun uploadAllHabits(uid: String): Int {
        // Snapshot under the list's lock: SQLiteHabitList.iterator() is
        // @Synchronized but hands out a live LinkedList iterator, so the
        // lock is released before toList() finishes iterating. Copying
        // while holding the same monitor prevents a
        // ConcurrentModificationException if the user edits a habit on the
        // main thread mid-upload.
        val habits: List<Habit> = synchronized(habitList) { habitList.toList() }
        // Upload in parallel instead of one document at a time: 19
        // sequential round-trips made a manual sync look frozen for
        // tens of seconds with no feedback at all. coroutineScope is
        // required here: uploadAllHabits is a plain suspend function,
        // not a CoroutineScope, so bare async {} does not compile.
        val jobs = coroutineScope {
            habits.map { habit ->
                async {
                    val uuid = habit.uuid ?: return@async
                    val doc = db.collection(USERS).document(uid)
                        .collection(HABITS).document(uuid)
                    doc.set(serializeHabit(habit)).await()
                }
            }
        }
        jobs.awaitAll()
        return habits.size
    }

    private fun scheduleAutoUpload() {
        autoUploadJob?.cancel()
        autoUploadJob = scope.launch {
            delay(AUTO_UPLOAD_DELAY_MS)
            if (!isSignedIn()) return@launch
            try {
                uploadAllHabits()
            } catch (e: Exception) {
                Log.w(TAG, "Auto-upload after local change failed", e)
            }
        }
    }

    /**
     * Downloads all habit documents for the user and merges them into the
     * local database. Returns (number of remote habits seen, number of
     * habits created locally).
     */
    private suspend fun downloadAndMerge(uid: String): Downloaded {
        // A missing Firestore database (or rejected rules) surfaces here
        // as FirebaseFirestoreException. Treat a failed download as
        // "nothing downloaded" instead of aborting the whole sync: the
        // failure is logged with an actionable hint and the upload below
        // still runs. (Check in the Firebase console that a Firestore
        // database exists in the project and that the user may read
        // users/{uid}/habits/*.)
        val snapshot = try {
            db.collection(USERS).document(uid)
                .collection(HABITS).get().await()
        } catch (e: Exception) {
            Log.w(TAG, "Download failed (Firestore database may not exist " +
                "in this Firebase project, or the rules reject the read)", e)
            return Downloaded(habits = 0, created = 0)
        }
        var created = 0
        // Apply the merge on the Main thread. HabitList notifies its
        // observers synchronously when habits/entries change, and the
        // list UI reads the live list on Main. Mutating the list from
        // Dispatchers.IO crashed the app: the UI was either touched
        // from the wrong thread (CalledFromWrongThreadException) or
        // iterated while the background thread changed it
        // (ConcurrentModificationException). Both escape the caller's
        // try/catch because they fire on the Main thread, not inside
        // the sync coroutine — which is why the app died with no toast.
        withContext(Dispatchers.Main) {
            isApplyingRemote = true
            try {
                for (doc in snapshot.documents) {
                    // One corrupt remote document must not abort the whole
                    // download: skip it, log it, and keep merging the rest.
                    try {
                        if (applyRemoteHabit(doc)) created++
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupt remote habit document ${doc.id}", e)
                    }
                }
            } finally {
                isApplyingRemote = false
            }
        }
        return Downloaded(habits = snapshot.size(), created = created)
    }

    /**
     * Applies one remote habit document to the local database.
     * @return true if a new local habit was created, false if the habit
     * already existed locally (its missing entries were merged in).
     */
    private fun applyRemoteHabit(doc: DocumentSnapshot): Boolean {
        val uuid = doc.id
        val remoteEntries = parseEntries(doc)
        val local = habitList.getByUUID(uuid)
        if (local == null) {
            val habit = modelFactory.buildHabit()
            habit.uuid = uuid
            applyFields(habit, doc)
            habitList.add(habit)
            // Position is reset to the end by HabitList.add(); restore the
            // remote position and persist it.
            habit.position = (doc.getLong("position")?.toInt() ?: 0)
            for (entry in remoteEntries) habit.originalEntries.add(entry)
            habit.recompute()
            habitList.update(habit)
            return true
        }
        // Existing habit: only add entries that are missing locally.
        // Local values win on conflict (see class docs).
        var changed = false
        for (entry in remoteEntries) {
            val existing = local.originalEntries.get(entry.date)
            if (existing.value == Entry.UNKNOWN) {
                local.originalEntries.add(entry)
                changed = true
            }
        }
        if (changed) {
            local.recompute()
            habitList.update(local)
        }
        return false
    }

    private fun applyFields(habit: Habit, doc: DocumentSnapshot) {
        habit.name = doc.getString("name") ?: ""
        habit.description = doc.getString("description") ?: ""
        habit.question = doc.getString("question") ?: ""
        habit.type = parseHabitType(doc.getString("type"))
        habit.color = PaletteColor((doc.getLong("color")?.toInt() ?: 8).coerceIn(0, 19))
        // A denominator of 0 would make Frequency produce an Infinity
        // score when the habit is recomputed; clamp it to 1.
        habit.frequency = Frequency(
            doc.getLong("frequencyNumerator")?.toInt() ?: 1,
            (doc.getLong("frequencyDenominator")?.toInt() ?: 1).coerceAtLeast(1)
        )
        habit.targetType = parseTargetType(doc.getString("targetType"))
        habit.targetValue = (doc.get("targetValue") as? Number)?.toDouble() ?: 0.0
        habit.unit = doc.getString("unit") ?: ""
        habit.isArchived = doc.getBoolean("isArchived") ?: false
        val reminderHour = doc.getLong("reminderHour")?.toInt()
        val reminderMinute = doc.getLong("reminderMinute")?.toInt()
        val reminderDays = doc.getLong("reminderDays")?.toInt()
        habit.reminder = if (reminderHour != null && reminderMinute != null && reminderDays != null) {
            Reminder(reminderHour, reminderMinute, WeekdayList(reminderDays))
        } else {
            null
        }
    }

    private fun parseEntries(doc: DocumentSnapshot): List<Entry> {
        val raw = doc.get("entries") as? Map<*, *> ?: return emptyList()
        val entries = ArrayList<Entry>(raw.size)
        for ((key, value) in raw) {
            val millis = (key as? String)?.toLongOrNull() ?: continue
            val fields = value as? Map<*, *> ?: continue
            val entryValue = (fields["value"] as? Number)?.toInt() ?: continue
            val notes = fields["notes"] as? String ?: ""
            entries.add(Entry(LocalDate.fromUnixTime(millis), entryValue, notes))
        }
        return entries
    }

    private fun serializeHabit(habit: Habit): Map<String, Any?> {
        val entries = HashMap<String, Map<String, Any?>>()
        for (entry in habit.originalEntries.getKnown()) {
            entries[entry.date.unixTime.toString()] = mapOf(
                "value" to entry.value,
                "notes" to entry.notes
            )
        }
        val reminder = habit.reminder
        return mapOf(
            "name" to habit.name,
            "description" to habit.description,
            "question" to habit.question,
            "type" to habit.type.name,
            "color" to habit.color.paletteIndex,
            "frequencyNumerator" to habit.frequency.numerator,
            "frequencyDenominator" to habit.frequency.denominator,
            "targetType" to habit.targetType.name,
            "targetValue" to habit.targetValue,
            "unit" to habit.unit,
            "isArchived" to habit.isArchived,
            "position" to habit.position,
            "reminderHour" to reminder?.hour,
            "reminderMinute" to reminder?.minute,
            "reminderDays" to reminder?.days?.toInteger(),
            "updatedAt" to System.currentTimeMillis(),
            "entries" to entries
        )
    }

    private fun parseHabitType(name: String?): HabitType {
        return try {
            if (name != null) HabitType.valueOf(name) else HabitType.YES_NO
        } catch (e: IllegalArgumentException) {
            HabitType.YES_NO
        }
    }

    private fun parseTargetType(name: String?): NumericalHabitType {
        return try {
            if (name != null) NumericalHabitType.valueOf(name) else NumericalHabitType.AT_LEAST
        } catch (e: IllegalArgumentException) {
            NumericalHabitType.AT_LEAST
        }
    }

    private data class Downloaded(val habits: Int, val created: Int)

    companion object {
        private const val TAG = "FirebaseSync"
        private const val USERS = "users"
        private const val HABITS = "habits"

        /** Local edits are uploaded this long after the last change, so a
         * burst of taps results in a single upload. */
        private const val AUTO_UPLOAD_DELAY_MS = 3000L

        // A manual sync must never hang silently with no feedback.
        private const val SYNC_TIMEOUT_MS = 60_000L
    }
}
