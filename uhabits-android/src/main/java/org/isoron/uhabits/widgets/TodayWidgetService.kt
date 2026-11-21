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
package org.isoron.uhabits.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.RemoteViewsService.RemoteViewsFactory
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.ui.views.WidgetTheme
import org.isoron.uhabits.core.utils.DateUtils.Companion.getTodayWithOffset
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.intents.PendingIntentFactory

/**
 * Backing service for the "Today" widget ([TodayWidgetProvider]). Each row
 * shows one active habit: its color dot, name, and whether it is done
 * today. Rows are plain RemoteViews (no bitmap rendering), so they stay
 * cheap to refresh.
 */
class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TodayRemoteViewsFactory(this.applicationContext, intent)
    }
}

internal class TodayRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsFactory {
    private val widgetId: Int = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var habits: List<Habit> = emptyList()

    override fun onCreate() {}

    override fun onDestroy() {}

    override fun onDataSetChanged() {
        val app = context.applicationContext as HabitsApplication
        habits = app.component.habitList.toList().filter { !it.isArchived }
    }

    override fun getCount(): Int = habits.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= habits.size) return null
        val habit = habits[position]
        val today = getTodayWithOffset()
        val done = habit.isCompletedToday()
        val views = RemoteViews(context.packageName, R.layout.widget_today_row)
        views.setTextViewText(R.id.widget_today_name, habit.name)
        views.setTextViewText(
            R.id.widget_today_status,
            if (done) {
                context.getString(R.string.widget_done)
            } else {
                context.getString(R.string.widget_not_done)
            }
        )
        val habitColor = WidgetTheme().color(habit.color).toInt()
        views.setInt(R.id.widget_today_dot, "setBackgroundColor", habitColor)
        views.setTextColor(
            R.id.widget_today_status,
            if (done) habitColor else Color.parseColor("#9E9E9E")
        )
        val factory = PendingIntentFactory(context, IntentFactory())
        val fillIn = if (habit.isNumerical) {
            factory.showNumberPickerFillIn(habit, today)
        } else {
            factory.toggleCheckmarkFillIn(habit, today)
        }
        views.setOnClickFillInIntent(R.id.widget_today_row, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews {
        return RemoteViews(context.packageName, R.layout.widget_today_row)
    }

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = habits[position].id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
