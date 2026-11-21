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

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.list.ListHabitsActivity
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.intents.PendingIntentFactory

/**
 * "Today" home-screen widget: a scrollable list of the user's active
 * habits with today's status. Tapping a habit checks it off for today
 * (boolean habits) or opens the number editor (numerical habits), using
 * the same WidgetReceiver intents as the existing checkmark widgets.
 * Tapping the header opens the app. The list refreshes whenever habits
 * change, because [WidgetUpdater] broadcasts updates to this provider
 * just like to the other widget providers.
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray
    ) {
        for (id in widgetIds) updateAppWidget(context, manager, id)
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val app = context.applicationContext as HabitsApplication
            val habits = app.component.habitList.toList().filter { !it.isArchived }
            val views = RemoteViews(context.packageName, R.layout.widget_today)

            // The service intent needs a unique data URI per widget id,
            // otherwise the system reuses one adapter for all instances.
            val serviceIntent = Intent(context, TodayWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.today_list, serviceIntent)
            views.setEmptyView(R.id.today_list, R.id.today_empty)

            // One tap template for the whole list. Like the stack checkmark
            // widget, a list containing any numerical habit uses the number
            // editor template for every row.
            val factory = PendingIntentFactory(context, IntentFactory())
            val template = if (habits.any { it.isNumerical }) {
                factory.showNumberPickerTemplate()
            } else {
                factory.toggleCheckmarkTemplate()
            }
            views.setPendingIntentTemplate(R.id.today_list, template)

            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, ListHabitsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.today_header, openApp)

            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.today_list)
        }
    }
}
