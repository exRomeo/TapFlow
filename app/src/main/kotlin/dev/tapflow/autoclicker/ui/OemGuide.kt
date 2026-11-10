package dev.tapflow.autoclicker.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phone makers add their own battery managers that kill background services, which is the
 * main reason auto clickers "stop working". These are the steps for each maker, plus the
 * settings screens to try opening. Those screens move between OS versions, so every
 * intent is a best guess that falls back to TapFlow's App info page.
 */
data class OemGuide(val brand: String, val steps: List<String>, val screens: List<ComponentName>)

fun oemGuideFor(manufacturer: String = Build.MANUFACTURER): OemGuide {
  return when (manufacturer.lowercase()) {
    "xiaomi", "redmi", "poco" -> OemGuide(
      brand = "Xiaomi",
      steps = listOf(
        "Security app → Permissions → Autostart → turn on TapFlow.",
        "App info → Battery saver → No restrictions.",
        "Open Recents, then pull down on TapFlow (or long-press it) and tap the lock.",
      ),
      screens = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
      ),
    )
    "samsung" -> OemGuide(
      brand = "Samsung",
      steps = listOf(
        "Settings → Battery → Background usage limits → Never sleeping apps → add TapFlow.",
        "Make sure TapFlow isn't in Sleeping apps or Deep sleeping apps.",
        "App info → Battery → Unrestricted.",
      ),
      screens = listOf(
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
      ),
    )
    "huawei", "honor" -> OemGuide(
      brand = "Huawei / Honor",
      steps = listOf(
        "Phone Manager → App launch → TapFlow → Manage manually, and turn on Auto-launch, Secondary launch and Run in background.",
        "Settings → Battery → turn off Power saving mode while running scripts.",
      ),
      screens = listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
      ),
    )
    "oppo", "realme", "oneplus" -> OemGuide(
      brand = "OPPO / realme / OnePlus",
      steps = listOf(
        "App info → Battery usage → turn on Allow background activity (and Allow auto launch).",
        "Open Recents, then tap the menu on TapFlow and choose Lock.",
      ),
      screens = listOf(
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
      ),
    )
    "vivo", "iqoo" -> OemGuide(
      brand = "vivo / iQOO",
      steps = listOf(
        "i Manager → App manager → Autostart manager → turn on TapFlow.",
        "Settings → Battery → Background power consumption → allow TapFlow.",
      ),
      screens = listOf(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
      ),
    )
    else -> OemGuide(
      brand = manufacturer.replaceFirstChar { it.uppercase() },
      steps = listOf(
        "App info → Battery → Unrestricted (or \"Don't optimize\").",
        "If your phone has its own battery or \"app launch\" manager, allow TapFlow to run in the background there.",
      ),
      screens = emptyList(),
    )
  }
}

/** Opens the first of the maker's settings screens that exists, else TapFlow's App info. */
fun openOemSettings(context: Context, guide: OemGuide) {
  for (screen in guide.screens) {
    val opened = runCatching {
      context.startActivity(Intent().setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (opened) return
  }
  context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
  )
}
