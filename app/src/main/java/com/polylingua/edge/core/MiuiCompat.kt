package com.polylingua.edge.core

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * MIUI / HyperOS survival kit. MIUI's "Battery saver" and missing "Autostart" permission kill
 * background audio and long downloads even with a foreground service, so the app asks for:
 *  1. battery-optimisation exemption (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), and
 *  2. the MIUI permission editor (miui.intent.action.APP_PERM_EDITOR) for Autostart and
 *     "Display pop-up windows while running in the background".
 */
object MiuiCompat {

    val isXiaomiFamily: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco") ||
            Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** "V140" style MIUI version, "OS1.0" on HyperOS, or null on other ROMs. */
    val miuiVersion: String? by lazy {
        readProp("ro.miui.ui.version.name")?.takeIf { it.isNotBlank() }
            ?: readProp("ro.mi.os.version.name")?.takeIf { it.isNotBlank() }
    }

    val isMiui: Boolean get() = miuiVersion != null || isXiaomiFamily

    private fun readProp(name: String): String? = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        BufferedReader(InputStreamReader(p.inputStream)).use { it.readLine()?.trim() }.also { p.waitFor() }
    }.getOrNull()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Candidate intents for the MIUI permission editor, most specific first. */
    fun permissionEditorIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        return listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Launches the first resolvable permission-editor intent. Returns false if none worked. */
    fun openPermissionEditor(context: Context): Boolean {
        for (intent in permissionEditorIntents(context)) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    fun requestBatteryExemption(context: Context): Boolean = try {
        context.startActivity(batteryOptimizationIntent(context))
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
