package org.lia.accessibility.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.text.Normalizer
import java.util.Locale

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String
)

class InstalledAppResolver(private val context: Context) {
    private val packageManager = context.packageManager

    fun listLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                LaunchableApp(
                    label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty(),
                    packageName = activity.packageName,
                    activityName = activity.name
                )
            }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { normalize(it.label) }
    }

    fun resolve(name: String): LaunchableApp? {
        val wanted = normalize(name)
        if (wanted.isBlank()) return null

        val apps = listLaunchableApps()

        apps.firstOrNull { normalize(it.label) == wanted }?.let { return it }
        apps.firstOrNull { normalize(it.packageName.substringAfterLast('.')) == wanted }?.let { return it }

        return apps
            .map { app -> app to similarityScore(wanted, app) }
            .filter { (_, score) -> score >= MIN_FUZZY_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    fun launch(name: String): Boolean {
        val app = resolve(name) ?: return false
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun similarityScore(wanted: String, app: LaunchableApp): Int {
        val label = normalize(app.label)
        val packageTail = normalize(app.packageName.substringAfterLast('.'))

        return maxOf(
            tokenScore(wanted, label),
            tokenScore(wanted, packageTail)
        )
    }

    private fun tokenScore(wanted: String, actual: String): Int =
        when {
            wanted == actual -> 100
            actual.startsWith(wanted) || wanted.startsWith(actual) -> 85
            actual.contains(wanted) || wanted.contains(actual) -> 70
            wanted.split(' ').any { token -> token.length >= 3 && actual.contains(token) } -> 55
            else -> 0
        }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    companion object {
        private const val MIN_FUZZY_SCORE = 55
    }
}
