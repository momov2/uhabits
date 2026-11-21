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
package org.isoron.uhabits.activities.habits.stats

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.dp

/**
 * Statistics & streaks overview, reachable from the main screen menu
 * ("Statistics"). Read-only: it only reads the local habit database and
 * never changes any data. All numbers are computed from the user's own
 * entries:
 * - Today: how many active habits are already done today.
 * - Last 30 days: completion rate across all active habits.
 * - Streaks: best current streak and longest streak ever.
 * - This month: one square per day so far, coloured by how many habits
 *   were completed that day (green = all, amber = some, grey = none).
 * - Per habit: current streak and 30-day completion rate.
 */
class StatisticsActivity : AppCompatActivity() {

    private lateinit var sres: StyledResources

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HabitsApplication
        AndroidThemeSwitcher(this, app.component.preferences).apply()
        sres = StyledResources(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.statistics)

        val habits = app.component.habitList.toList().filter { !it.isArchived }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }
        if (habits.isEmpty()) {
            root.addView(bodyText(getString(R.string.no_habits)))
        } else {
            addTodaySection(root, habits)
            addLast30DaysSection(root, habits)
            addStreakSection(root, habits)
            addMonthSection(root, habits)
            addPerHabitSection(root, habits)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun addTodaySection(root: LinearLayout, habits: List<Habit>) {
        val today = getToday()
        val done = habits.count { isCompletedOn(it, today) }
        root.addView(sectionTitle(getString(R.string.stats_today)))
        root.addView(bigNumber("$done / ${habits.size}"))
        root.addView(bodyText(getString(R.string.stats_today_done, done, habits.size)))
    }

    private fun addLast30DaysSection(root: LinearLayout, habits: List<Habit>) {
        val today = getToday()
        val from = today.minus(29)
        var completed = 0
        var total = 0
        for (habit in habits) {
            var day = from
            while (!day.isNewerThan(today)) {
                total++
                if (isCompletedOn(habit, day)) completed++
                day = day.plus(1)
            }
        }
        val rate = if (total > 0) (completed * 100 / total) else 0
        root.addView(sectionTitle(getString(R.string.stats_last_30_days)))
        root.addView(bigNumber("$rate%"))
        root.addView(
            bodyText(getString(R.string.stats_completion_rate, completed, total, rate))
        )
    }

    private fun addStreakSection(root: LinearLayout, habits: List<Habit>) {
        val currentBest = habits.maxOfOrNull { currentStreak(it) } ?: 0
        val longestEver = habits.maxOfOrNull { longestStreak(it) } ?: 0
        root.addView(sectionTitle(getString(R.string.stats_streaks)))
        root.addView(
            bodyText(getString(R.string.stats_best_current_streak, currentBest))
        )
        root.addView(bodyText(getString(R.string.stats_longest_streak, longestEver)))
    }

    private fun addMonthSection(root: LinearLayout, habits: List<Habit>) {
        val today = getToday()
        // Day of the month taken from the app's own "today" (which honors
        // the midnight-delay setting), not from the wall clock.
        val dayOfMonth = today.day
        val firstOfMonth = today.minus(dayOfMonth - 1)
        root.addView(sectionTitle(getString(R.string.stats_this_month)))
        val grid = GridLayout(this).apply {
            columnCount = 7
            useDefaultMargins = false
        }
        for (day in 1..dayOfMonth) {
            val timestamp = firstOfMonth.plus(day - 1)
            val done = habits.count { isCompletedOn(it, timestamp) }
            val cell = View(this).apply {
                val size = dp(28f).toInt()
                val margin = dp(3f).toInt()
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                setBackgroundColor(
                    when {
                        habits.isEmpty() -> Color.TRANSPARENT
                        done == habits.size -> Color.parseColor("#43A047")
                        done > 0 -> Color.parseColor("#FB8C00")
                        else -> Color.parseColor("#424242")
                    }
                )
            }
            grid.addView(cell)
        }
        root.addView(grid)
        root.addView(
            bodyText(getString(R.string.stats_month_legend))
        )
    }

    private fun addPerHabitSection(root: LinearLayout, habits: List<Habit>) {
        root.addView(sectionTitle(getString(R.string.stats_per_habit)))
        val today = getToday()
        val from = today.minus(29)
        val sorted = habits.sortedByDescending { currentStreak(it) }
        for (habit in sorted) {
            var completed = 0
            var day = from
            while (!day.isNewerThan(today)) {
                if (isCompletedOn(habit, day)) completed++
                day = day.plus(1)
            }
            val rate = completed * 100 / 30
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val margin = dp(4f).toInt()
                setPadding(0, margin, 0, margin)
            }
            val name = TextView(this).apply {
                text = habit.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(sres.getColor(R.attr.contrast100))
            }
            val detail = bodyText(
                getString(
                    R.string.stats_habit_detail,
                    currentStreak(habit),
                    rate
                )
            )
            row.addView(name)
            row.addView(detail)
            root.addView(row)
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(sres.getColor(R.attr.contrast100))
            val margin = dp(16f).toInt()
            setPadding(0, margin, 0, dp(4f).toInt())
        }
    }

    private fun bigNumber(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            setTextColor(sres.getColor(R.attr.contrast100))
        }
    }

    private fun bodyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(sres.getColor(R.attr.contrast60))
        }
    }

    companion object {
        /**
         * Whether the habit counts as completed on the given day, using the
         * entries the user actually recorded (not the auto-filled computed
         * entries).
         */
        fun isCompletedOn(habit: Habit, timestamp: LocalDate): Boolean {
            val value = habit.originalEntries.get(timestamp).value
            return if (habit.isNumerical) {
                when (habit.targetType) {
                    NumericalHabitType.AT_LEAST -> value / 1000.0 >= habit.targetValue
                    NumericalHabitType.AT_MOST ->
                        value != Entry.UNKNOWN && value / 1000.0 <= habit.targetValue
                }
            } else {
                value == Entry.YES_MANUAL
            }
        }

        /**
         * Length (in days) of the streak the user is currently on: the
         * streak whose last day is today or yesterday, or 0.
         */
        fun currentStreak(habit: Habit): Int {
            val today = getToday()
            val yesterday = today.minus(1)
            for (streak in habit.streaks.getBest(25)) {
                if (!streak.end.isOlderThan(yesterday)) return streak.length
            }
            return 0
        }

        fun longestStreak(habit: Habit): Int {
            return habit.streaks.getBest(1).firstOrNull()?.length ?: 0
        }
    }
}
