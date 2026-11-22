/*
 * Copyright (C) 2016-2021 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.activities.settings

import android.app.backup.BackupManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.RESULT_BUG_REPORT
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_CSV
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_DB
import org.isoron.uhabits.activities.habits.list.RESULT_IMPORT_DATA
import org.isoron.uhabits.activities.habits.list.RESULT_REPAIR_DB
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.notifications.AndroidNotificationTray.Companion.createAndroidNotificationChannel
import org.isoron.uhabits.notifications.RingtoneManager
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.startActivitySafely
import org.isoron.uhabits.widgets.WidgetUpdater
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    private var sharedPrefs: SharedPreferences? = null
    private var ringtoneManager: RingtoneManager? = null
    private lateinit var prefs: Preferences
    private var widgetUpdater: WidgetUpdater? = null

    // Coroutine scope for Firebase sign-in / sync actions, cancelled when
    // this fragment is destroyed. Uses MainScope (kotlinx-coroutines-android,
    // already a dependency) so no extra lifecycle library is needed.
    private val uiScope = MainScope()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RINGTONE_REQUEST_CODE) {
            ringtoneManager!!.update(data)
            updateRingtoneDescription()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        val appContext = requireContext().applicationContext
        if (appContext is HabitsApplication) {
            prefs = appContext.component.preferences
            widgetUpdater = appContext.component.widgetUpdater
        }
        setResultOnPreferenceClick("importData", RESULT_IMPORT_DATA)
        setResultOnPreferenceClick("exportCSV", RESULT_EXPORT_CSV)
        setResultOnPreferenceClick("exportDB", RESULT_EXPORT_DB)
        setResultOnPreferenceClick("repairDB", RESULT_REPAIR_DB)
        setResultOnPreferenceClick("bugReport", RESULT_BUG_REPORT)
    }

    override fun onCreatePreferences(bundle: Bundle?, s: String?) {
        // NOP
    }

    override fun onPause() {
        sharedPrefs!!.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sr = StyledResources(context!!)
        view.setBackgroundColor(sr.getColor(R.attr.contrast0))
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val key = preference.key ?: return false
        when (key) {
            "reminderSound" -> {
                showRingtonePicker()
                return true
            }
            "reminderCustomize" -> {
                createAndroidNotificationChannel(requireContext())
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, NotificationTray.REMINDERS_CHANNEL_ID)
                startActivity(intent)
                return true
            }
            "rateApp" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.playStoreURL)))
                activity?.startActivitySafely(intent)
                return true
            }
            "signInGoogle" -> {
                signInWithGoogle()
                return true
            }
            "signOutGoogle" -> {
                HabitsApplication.firebaseAuthManager?.signOut()
                Toast.makeText(requireContext(), R.string.signed_out, Toast.LENGTH_LONG).show()
                updateAccountStatus()
                return true
            }
            "syncNow" -> {
                syncNow()
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onResume() {
        super.onResume()
        ringtoneManager = RingtoneManager(requireActivity())
        sharedPrefs = preferenceManager.sharedPreferences
        sharedPrefs!!.registerOnSharedPreferenceChangeListener(this)
        if (!prefs.isDeveloper) {
            val devCategory = findPreference("devCategory") as PreferenceCategory
            devCategory.isVisible = false
        }
        updateWeekdayPreference()
        updateAccountStatus()

        findPreference("reminderSound").isVisible = false
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /**
     * Shows the signed-in Google account (or "not signed in") and shows
     * only the actions that make sense for that state.
     */
    private fun updateAccountStatus() {
        val user = HabitsApplication.firebaseAuthManager?.currentUser
        val statusPref = findPreference("accountStatus")
        statusPref.summary = if (user != null) {
            getString(R.string.account_signed_in_as, user.email ?: user.uid)
        } else {
            getString(R.string.account_not_signed_in)
        }
        findPreference("signInGoogle").isVisible = user == null
        findPreference("signOutGoogle").isVisible = user != null
        findPreference("syncNow").isVisible = user != null
    }

    private fun signInWithGoogle() {
        val auth = HabitsApplication.firebaseAuthManager
        if (auth == null) {
            // Firebase failed to initialise at startup; the app keeps
            // running offline, so sign-in is simply unavailable.
            Toast.makeText(
                requireContext(),
                getString(R.string.sign_in_failed, "Firebase tidak tersedia"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        uiScope.launch {
            val result = auth.signIn(requireActivity())
            result.onSuccess { user ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sign_in_success, user.email ?: user.uid),
                    Toast.LENGTH_LONG
                ).show()
                updateAccountStatus()
                // First sync right after login: download the cloud state
                // (e.g. after a reinstall), then upload the merged state.
                syncNow()
            }.onFailure { e ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sign_in_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun syncNow() {
        val sync = HabitsApplication.firebaseSyncManager
        if (sync == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.sync_failed, "Firebase tidak tersedia"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!sync.isSignedIn()) {
            Toast.makeText(requireContext(), R.string.sync_not_signed_in, Toast.LENGTH_LONG).show()
            return
        }
        uiScope.launch {
            try {
                val stats = sync.syncNow()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sync_success, stats.uploaded, stats.downloaded),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                // Local data is untouched by a failed sync; just report it.
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sync_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateWeekdayPreference() {
        val weekdayPref = findPreference("pref_first_weekday") as ListPreference
        val currentFirstWeekday = prefs.firstWeekday.daysSinceSunday + 1
        // Upstream dev removed DateUtils.getLongWeekdayNames; the
        // WeekdayPickerDialog on dev uses JavaLocalDateFormatter instead.
        val dayNames = JavaLocalDateFormatter(Locale.getDefault())
            .longWeekdayNames(DayOfWeek.SATURDAY)
        val dayValues = arrayOf("7", "1", "2", "3", "4", "5", "6")
        weekdayPref.entries = dayNames
        weekdayPref.entryValues = dayValues
        weekdayPref.setDefaultValue(currentFirstWeekday.toString())
        weekdayPref.summary = dayNames[currentFirstWeekday % 7]
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        if (key == "pref_widget_opacity" && widgetUpdater != null) {
            Log.d("SettingsFragment", "updating widgets")
            widgetUpdater!!.updateWidgets()
        }
        BackupManager.dataChanged("org.isoron.uhabits")
        updateWeekdayPreference()
    }

    private fun setResultOnPreferenceClick(key: String, result: Int) {
        val pref = findPreference(key)
        pref.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                requireActivity().setResult(result)
                requireActivity().finish()
                true
            }
    }

    private fun showRingtonePicker() {
        val existingRingtoneUri = ringtoneManager!!.getURI()
        val defaultRingtoneUri = Settings.System.DEFAULT_NOTIFICATION_URI
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
            android.media.RingtoneManager.TYPE_NOTIFICATION
        )
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            defaultRingtoneUri
        )
        intent.putExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            existingRingtoneUri
        )
        startActivityForResult(intent, RINGTONE_REQUEST_CODE)
    }

    private fun updateRingtoneDescription() {
        val ringtoneName = ringtoneManager!!.getName() ?: return
        val ringtonePreference = findPreference("reminderSound")
        ringtonePreference.summary = ringtoneName
    }

    companion object {
        private const val RINGTONE_REQUEST_CODE = 1
    }
}
