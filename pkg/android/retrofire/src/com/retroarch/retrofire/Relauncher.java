package com.retroarch.retrofire;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The system kills RetroFire while it replaces the APK, so after a self-update the user would be
 * left on the installer's "app installed" screen. MY_PACKAGE_REPLACED arrives in the new build:
 * when the update was started by {@link Updater} (not by adb or another installer) the app is
 * opened again. Starting an activity from a receiver is allowed up to API 28 (all Fire OS versions).
 */
public final class Relauncher extends BroadcastReceiver {
  @Override
  public void onReceive(Context ctx, Intent intent) {
    if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()) || !Updater.takeRelaunch(ctx)) return;
    try {
      Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
      if (open != null) ctx.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (Exception e) {
      Log.w("RetroFire", "relaunch after update failed: " + e);
    }
  }
}
