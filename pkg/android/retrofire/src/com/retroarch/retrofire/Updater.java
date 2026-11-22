package com.retroarch.retrofire;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * RetroFire self-update without an app store (same scheme as FireBro).
 *
 * {@link #MANIFEST_URL}, a file in the public repository mefistofelix/firetv-apps whose releases
 * "retrofire-N" hold the APKs (written by pkg/android/retrofire/publish-update.sh), describes the
 * newest build:
 * {"versionCode": C, "versionName": "1.22.2-rfN", "url": "<apk>", "sha256": "<hex>", "size": bytes}.
 * A newer build is offered in a dialog when the app starts from the launcher (not when a game is
 * started directly). The APK is downloaded to the cache, checked against the SHA-256 and handed to
 * the system installer through a PackageInstaller session, whose own screen asks for the final
 * "Installa" (apps cannot install silently). Android itself refuses an APK that is not signed with
 * the same key. After the update {@link Relauncher} opens the app again.
 */
public final class Updater {
  private static final String TAG = "RetroFire";
  public static final String MANIFEST_URL =
      "https://raw.githubusercontent.com/mefistofelix/firetv-apps/main/retrofire.json";
  private static final String ACTION_RESULT = "com.retroarch.retrofire.UPDATE_RESULT";
  private static final long MIN_INTERVAL_MS = 2 * 60_000L;
  private static final long SNOOZE_MS = 12L * 3600 * 1000;
  private static final long MAX_BYTES = 200L * 1024 * 1024;
  private static final String RELAUNCH_AT = "relaunchAt";
  private static final long RELAUNCH_WINDOW_MS = 15 * 60_000L;

  private static volatile boolean busy;
  private static AlertDialog progressDialog;

  static final class Release {
    final long code; final String name, url, sha256; final long size;
    Release(long code, String name, String url, String sha256, long size) {
      this.code = code; this.name = name; this.url = url; this.sha256 = sha256; this.size = size;
    }
  }

  private Updater() {}

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getSharedPreferences("update", Context.MODE_PRIVATE);
  }

  @SuppressWarnings("deprecation")
  private static long installedCode(Context ctx) {
    try {
      return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
    } catch (Exception e) {
      return 0;
    }
  }

  private static String installedName(Context ctx) {
    try {
      return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "?";
    }
  }

  /** Called by the game activity when it is created: checks in the background, offers a newer build. */
  public static void onStart(final Activity activity) {
    if (Build.VERSION.SDK_INT < 21 || busy) return;
    if (activity.getIntent() != null && activity.getIntent().hasExtra("ROM")) return; // a game started directly
    final SharedPreferences p = prefs(activity);
    long now = System.currentTimeMillis();
    // many TVs on one network: at most one check every MIN_INTERVAL_MS
    long last = p.getLong("checkedAt", 0);
    if (now - last >= 0 && now - last < MIN_INTERVAL_MS) return;
    p.edit().putLong("checkedAt", now).apply();
    new Thread(new Runnable() {
      @Override public void run() {
        final Release r = fetchManifest();
        if (r == null || r.code <= installedCode(activity) || snoozed(activity, r)) return;
        activity.runOnUiThread(new Runnable() {
          @Override public void run() { offer(activity, r); }
        });
      }
    }, "update-check").start();
  }

  private static Release fetchManifest() {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
      c.setConnectTimeout(8000);
      c.setReadTimeout(8000);
      c.setUseCaches(false);
      c.setRequestProperty("User-Agent", "RetroFire");
      if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
      InputStream in = c.getInputStream();
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int k;
      while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
      in.close();
      JSONObject j = new JSONObject(b.toString("UTF-8"));
      return new Release(j.getLong("versionCode"), j.optString("versionName"), j.getString("url"),
          j.getString("sha256").toLowerCase(Locale.ROOT), j.optLong("size", -1));
    } catch (Exception e) {
      Log.w(TAG, "update check failed: " + e);
      return null;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private static boolean snoozed(Context ctx, Release r) {
    SharedPreferences p = prefs(ctx);
    return p.getLong("snoozeCode", 0) == r.code
        && System.currentTimeMillis() - p.getLong("snoozeAt", 0) < SNOOZE_MS;
  }

  private static void snooze(Context ctx, Release r) {
    prefs(ctx).edit().putLong("snoozeCode", r.code).putLong("snoozeAt", System.currentTimeMillis()).apply();
  }

  private static AlertDialog.Builder builder(Activity a) {
    return new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
  }

  private static void offer(final Activity a, final Release r) {
    if (a.isFinishing()) return;
    String size = r.size > 0 ? String.format(Locale.ITALY, " (%.1f MB)", r.size / 1048576.0) : "";
    AlertDialog d = builder(a)
        .setTitle("Aggiornamento disponibile")
        .setMessage("È disponibile RetroFire " + r.name + size + ". Installata: " + installedName(a) + ".\n\n"
            + "Dopo lo scaricamento si apre la schermata di installazione di Fire TV: conferma con «Installa». "
            + "Giochi, salvataggi e impostazioni restano.")
        .setPositiveButton("Installa", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface di, int w) { start(a, r); }
        })
        .setNegativeButton("Più tardi", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface di, int w) { snooze(a, r); }
        })
        .setOnCancelListener(new DialogInterface.OnCancelListener() {
          @Override public void onCancel(DialogInterface di) { snooze(a, r); }
        })
        .create();
    d.show();
    d.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
  }

  private static void start(final Activity a, final Release r) {
    if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
      askInstallPermission(a, r);
      return;
    }
    final TextView note = new TextView(a);
    note.setTextSize(16f);
    int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
    note.setPadding(pad, pad / 2, pad, pad / 3);
    note.setText("Scaricamento… 0%");
    final AlertDialog d = builder(a).setTitle("RetroFire " + r.name).setView(note).setCancelable(false).create();
    d.show();
    progressDialog = d;
    busy = true;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Context app = a.getApplicationContext();
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          File apk = new File(app.getCacheDir(), "update.apk");
          download(r, apk, new Progress() {
            @Override public void at(final int pct) {
              ui.post(new Runnable() { @Override public void run() { note.setText("Scaricamento… " + pct + "%"); } });
            }
          });
          commit(app, apk);
        } catch (final Exception e) {
          Log.w(TAG, "update failed: " + e);
          ui.post(new Runnable() {
            @Override public void run() {
              closeProgress();
              toast(app, "Aggiornamento non riuscito: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
          });
        } finally {
          busy = false;
        }
      }
    }, "update-download").start();
  }

  private interface Progress { void at(int pct); }

  private static void download(Release r, File out, Progress progress) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    String address = r.url;
    HttpURLConnection c = null;
    // GitHub release downloads redirect to another host: follow by hand (HTTPS to HTTPS)
    for (int hop = 0; hop < 5; hop++) {
      c = (HttpURLConnection) new URL(address).openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(10000);
      c.setReadTimeout(20000);
      c.setRequestProperty("User-Agent", "RetroFire");
      int code = c.getResponseCode();
      if (code / 100 == 3 && c.getHeaderField("Location") != null) {
        address = new URL(new URL(address), c.getHeaderField("Location")).toString();
        c.disconnect();
        continue;
      }
      if (code != 200) throw new IllegalStateException("HTTP " + code);
      break;
    }
    long total = r.size > 0 ? r.size : c.getContentLength();
    InputStream in = c.getInputStream();
    OutputStream o = new FileOutputStream(out);
    try {
      byte[] buf = new byte[64 * 1024];
      long done = 0;
      int shown = -1, k;
      while ((k = in.read(buf)) >= 0) {
        o.write(buf, 0, k);
        md.update(buf, 0, k);
        done += k;
        if (done > MAX_BYTES) throw new IllegalStateException("file troppo grande");
        int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        if (pct != shown) { shown = pct; progress.at(pct); }
      }
    } finally {
      o.close();
      in.close();
      c.disconnect();
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
    if (!hex.toString().equals(r.sha256)) {
      out.delete();
      throw new IllegalStateException("file arrivato danneggiato");
    }
  }

  private static void commit(final Context app, File apk) throws Exception {
    PackageInstaller pi = app.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params =
        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(app.getPackageName());
    params.setSize(apk.length());
    int id = pi.createSession(params);
    PackageInstaller.Session s = pi.openSession(id);
    try {
      InputStream in = new FileInputStream(apk);
      OutputStream o = s.openWrite("base.apk", 0, apk.length());
      byte[] buf = new byte[64 * 1024];
      int k;
      while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
      s.fsync(o);
      o.close();
      in.close();
      // the installer's answer comes back as a broadcast to this process
      app.registerReceiver(new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) { handleResult(ctx, intent, this); }
      }, new IntentFilter(ACTION_RESULT));
      Intent back = new Intent(ACTION_RESULT).setPackage(app.getPackageName());
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 31) flags |= 0x02000000; // FLAG_MUTABLE (compileSdk 30 lacks the name)
      // read by Relauncher once the new build is in place: reopen RetroFire (only after our own update)
      prefs(app).edit().putLong(RELAUNCH_AT, System.currentTimeMillis()).commit();
      s.commit(PendingIntent.getBroadcast(app, id, back, flags).getIntentSender());
    } finally {
      s.close();
      apk.delete();
    }
  }

  @SuppressWarnings("deprecation")
  private static void handleResult(Context ctx, Intent intent, BroadcastReceiver self) {
    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
      if (confirm == null) {
        done(ctx, self, "Installazione non avviata");
        return;
      }
      try {
        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      } catch (Exception e) {
        done(ctx, self, "Installazione non avviata: " + e);
      }
      return; // the next answer (success, abort, failure) comes to the same receiver
    }
    if (status == PackageInstaller.STATUS_SUCCESS) { closeProgress(); return; } // Relauncher reopens the app
    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) { done(ctx, self, "Aggiornamento annullato"); return; }
    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
    done(ctx, self, "Aggiornamento non riuscito: " + (msg != null ? msg : "errore " + status));
  }

  private static void done(Context ctx, BroadcastReceiver self, String message) {
    try { ctx.getApplicationContext().unregisterReceiver(self); } catch (Exception e) { /* already gone */ }
    prefs(ctx).edit().remove(RELAUNCH_AT).apply();
    closeProgress();
    if (message != null) toast(ctx, message);
  }

  private static void closeProgress() {
    if (progressDialog != null) {
      try { progressDialog.dismiss(); } catch (Exception e) { /* activity gone */ }
      progressDialog = null;
    }
  }

  private static void toast(Context ctx, String text) {
    Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_LONG).show();
  }

  /** Fire OS 7 (Android 8+): installing needs the "Installa app sconosciute" switch for RetroFire, once. */
  private static void askInstallPermission(final Activity a, final Release r) {
    AlertDialog d = builder(a)
        .setTitle("Permesso per installare")
        .setMessage("Per aggiornarsi RetroFire deve poter installare app. Serve una volta sola:\n\n"
            + "Impostazioni → La mia Fire TV → Opzioni sviluppatore → Installa app sconosciute → RetroFire: ATTIVO\n\n"
            + "Poi riapri RetroFire e premi di nuovo «Installa».")
        .setPositiveButton("Apri impostazioni", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface di, int w) {
            prefs(a).edit().remove("checkedAt").apply(); // offer again at the next start
            Intent[] tries = {
                new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + a.getPackageName())),
                new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
                new Intent(Settings.ACTION_SETTINGS) };
            for (Intent i : tries) {
              try { a.startActivity(i); return; } catch (Exception e) { /* next */ }
            }
            toast(a, "Apri le impostazioni dalla schermata Home di Fire TV");
          }
        })
        .setNegativeButton("Più tardi", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface di, int w) { snooze(a, r); }
        })
        .create();
    d.show();
    d.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus();
  }

  /** True (once) when an update started by RetroFire in the last minutes has just been installed. */
  static boolean takeRelaunch(Context ctx) {
    SharedPreferences p = prefs(ctx);
    long at = p.getLong(RELAUNCH_AT, 0);
    if (at == 0) return false;
    p.edit().remove(RELAUNCH_AT).commit();
    long age = System.currentTimeMillis() - at;
    return age >= 0 && age < RELAUNCH_WINDOW_MS;
  }
}
