package com.mkred.xrayclient.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val pkg: String,
    val label: String,
    val system: Boolean,
)

/** Список установленных приложений, которым нужен интернет. */
object InstalledApps {
    private val icons = ConcurrentHashMap<String, Bitmap>()

    suspend fun load(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
            .mapNotNull { info ->
                val ai = info.applicationInfo ?: return@mapNotNull null
                AppInfo(
                    pkg = info.packageName,
                    label = ai.loadLabel(pm).toString(),
                    system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                        ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun icon(context: Context, pkg: String): Bitmap? = icons[pkg] ?: withContext(Dispatchers.IO) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96) }
            .getOrNull()
            ?.also { icons[pkg] = it }
    }
}
