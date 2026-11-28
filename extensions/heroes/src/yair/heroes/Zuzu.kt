package yair.heroes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the IDF's Zuzu app, or its website when the app isn't installed. */
internal object Zuzu {
    private const val PACKAGE = "il.idf.zuzu"
    private const val WEBSITE = "https://zuzu.prat.idf.il"

    fun open(context: Context) {
        // Starting another app's main screen needs no package visibility; it throws when the app isn't there.
        val app = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(app)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: ActivityNotFoundException) {
                // No browser either: nothing to open.
            }
        }
    }
}
