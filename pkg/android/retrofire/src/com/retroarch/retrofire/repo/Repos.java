package com.retroarch.retrofire.repo;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.retroarch.browser.preferences.util.UserPreferences;

import io.nayuki.qrcodegen.QrCode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Remote repositories (REPOS.md in the RetroFire project): the user's list of repositories,
 * their indexes, the game list they add to the grid, and the download of a game with the core,
 * BIOS and RetroFire config it needs.
 *
 * Entry points, called by RetroActivityCommon through reflection (RetroFire flavor only):
 * {@link #open} (grid side menu "Repository"), {@link #launch} (a game not downloaded yet:
 * playlist path "/rfrepo/<repo id>/<path of its main file>") and {@link #onStart}.
 * When a game is ready the activity's native repoStart(content, core) loads it; an empty
 * content only makes the menu reload the game lists.
 *
 * Where things go: games (roms/, disks/) to the app's folder on a USB disk when there is one
 * (<disk>/Android/data/<package>/files/RetroFire-repo), else to internal storage; cores to the
 * app's internal cores folder; everything else (config, BIOS, pad profiles) to the RetroFire
 * home, <files>/RetroFire, or to the folders the current config names when they are writable.
 */
public final class Repos {
  static final String TAG = "RetroFire";
  static final String PREFIX = "/rfrepo/";
  static final long MARGIN = 64L << 20;            // keep this much free on each disk
  static final long INDEX_MAX_AGE = 6L * 3600 * 1000;

  private Repos() {}

  // ================================================================ state

  static final Object LOCK = new Object();

  static File stateDir(Context c) { File d = new File(c.getFilesDir(), "repos"); d.mkdirs(); return d; }
  static File home(Context c) { File d = new File(c.getFilesDir(), "RetroFire"); d.mkdirs(); return d; }

  static JSONObject loadState(Context c) {
    synchronized (LOCK) {
      try {
        File f = new File(stateDir(c), "state.json");
        if (f.isFile()) return new JSONObject(readFile(f));
      } catch (Exception e) {
        Log.w(TAG, "repos: state unreadable: " + e);
      }
      try {
        return new JSONObject().put("repos", new JSONArray()).put("installed", new JSONObject());
      } catch (org.json.JSONException e) {
        throw new RuntimeException(e);
      }
    }
  }

  static void saveState(Context c, JSONObject s) {
    synchronized (LOCK) {
      try {
        writeFile(new File(stateDir(c), "state.json"), s.toString(1).getBytes("UTF-8"));
      } catch (Exception e) {
        Log.w(TAG, "repos: state not saved: " + e);
      }
    }
  }

  static JSONObject index(Context c, String repoId) {
    try {
      File f = new File(stateDir(c), repoId + ".json");
      return f.isFile() ? new JSONObject(readFile(f)) : null;
    } catch (Exception e) {
      return null;
    }
  }

  static String readFile(File f) throws IOException {
    InputStream in = new java.io.FileInputStream(f);
    try { return new String(readAll(in), "UTF-8"); } finally { in.close(); }
  }

  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    byte[] buf = new byte[65536];
    int k;
    while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
    return b.toByteArray();
  }

  static void writeFile(File f, byte[] data) throws IOException {
    f.getParentFile().mkdirs();
    File tmp = new File(f.getPath() + ".tmp");
    OutputStream o = new FileOutputStream(tmp);
    try { o.write(data); } finally { o.close(); }
    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
  }

  // ================================================================ the app's config

  /** key -> value (unquoted) of the app's retroarch.cfg. */
  static Map<String, String> config(Context c) {
    Map<String, String> kv = new HashMap<String, String>();
    try {
      BufferedReader br = new BufferedReader(new FileReader(UserPreferences.getDefaultConfigPath(c)));
      try {
        String line;
        while ((line = br.readLine()) != null) {
          int eq = line.indexOf('=');
          if (eq <= 0 || line.trim().startsWith("#")) continue;
          String v = line.substring(eq + 1).trim();
          if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
          kv.put(line.substring(0, eq).trim(), v);
        }
      } finally {
        br.close();
      }
    } catch (IOException e) {
      Log.w(TAG, "repos: app config unreadable: " + e);
    }
    return kv;
  }

  static boolean writable(File d) {
    if (d == null) return false;
    d.mkdirs();
    if (!d.isDirectory()) return false;
    File probe = new File(d, ".rf-write-test");
    try {
      new FileOutputStream(probe).close();
      probe.delete();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** A folder named by the config if the app can write there, else <home>/<fallback>. */
  static File folder(Context c, Map<String, String> cfg, String key, String fallback) {
    String v = cfg.get(key);
    if (v != null && !v.isEmpty() && !v.equals("default")) {
      File d = new File(v);
      if (writable(d)) return d;
    }
    File d = new File(home(c), fallback);
    d.mkdirs();
    return d;
  }

  static File coresDir(Context c, Map<String, String> cfg) {
    String v = cfg.get("libretro_directory");
    File d = v != null && !v.isEmpty() && !v.equals("default") ? new File(v) : new File(c.getApplicationInfo().dataDir, "cores");
    d.mkdirs();
    return d;
  }

  /** Games go to a removable disk when there is one (the app's own folder on it). */
  static File contentRoot(Context c) {
    if (Build.VERSION.SDK_INT >= 21) {
      File[] dirs = c.getExternalFilesDirs(null);
      if (dirs != null) {
        for (int i = 1; i < dirs.length; i++) {
          File d = dirs[i];
          if (d == null) continue;
          try {
            if (Environment.isExternalStorageRemovable(d) && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(d))) {
              File r = new File(d, "RetroFire-repo");
              if (writable(r)) return r;
            }
          } catch (IllegalArgumentException e) { /* not a storage volume */ }
        }
      }
    }
    return home(c);
  }

  static boolean hasDiskConfig(Context c) {
    File[] mounted = new File("/storage").listFiles();
    if (mounted == null) return false;
    for (File m : mounted)
      if (new File(m, "RetroFire/retroarch.cfg").isFile()) return true;
    return false;
  }

  // ================================================================ repositories

  static final class Repo {
    final JSONObject j;
    Repo(JSONObject j) { this.j = j; }
    String id() { return j.optString("id"); }
    String name() { return j.optString("name", "Repository"); }
    String url() { return j.optString("url"); }

    Crypto.Keys keys(Crypto.Header h) throws Exception {
      String salt = Base64.encodeToString(h.salt, Base64.NO_WRAP);
      if (salt.equals(j.optString("salt")) && j.optInt("iter") == h.iterations && j.has("enc"))
        return new Crypto.Keys(h.salt, h.iterations, Base64.decode(j.getString("enc"), Base64.NO_WRAP),
            Base64.decode(j.getString("names"), Base64.NO_WRAP));
      Crypto.Keys k = Crypto.derive(j.getString("password"), h.salt, h.iterations);
      j.put("salt", salt).put("iter", h.iterations)
          .put("enc", Base64.encodeToString(k.enc, Base64.NO_WRAP))
          .put("names", Base64.encodeToString(k.names, Base64.NO_WRAP));
      return k;
    }
  }

  static List<Repo> repos(JSONObject state) {
    List<Repo> out = new ArrayList<Repo>();
    JSONArray a = state.optJSONArray("repos");
    for (int i = 0; a != null && i < a.length(); i++) out.add(new Repo(a.optJSONObject(i)));
    return out;
  }

  static Repo repoById(JSONObject state, String id) {
    for (Repo r : repos(state)) if (r.id().equals(id)) return r;
    return null;
  }

  /**
   * Downloads and decrypts a repository's index when it changed (or force); stores it in
   * repos/<id>.json. Returns the index. The repository JSON gets its id and name from it.
   */
  /** Progress text for the user (any thread). */
  interface Note { void say(String text); }

  static JSONObject refreshIndex(Context c, Repo r, boolean force) throws Exception {
    return refreshIndex(c, r, force, null);
  }

  static JSONObject refreshIndex(Context c, Repo r, boolean force, final Note note) throws Exception {
    Source src = Source.of(r.url());
    Map<String, Source.Remote> listing = src.list();
    Source.Remote ir = listing.get("index.rfi");
    String stamp = ir != null ? ir.size + "/" + ir.time : "";
    JSONObject cached = r.id().isEmpty() ? null : index(c, r.id());
    if (!force && cached != null && !stamp.isEmpty() && stamp.equals(r.j.optString("stamp")))
      return cached;
    final long total = ir != null ? ir.size : 0;
    if (note != null) note.say("Scarico l'elenco dei giochi…");
    InputStream raw = src.open("index.rfi", listing);
    if (note != null) {
      raw = new FilterInputStream(raw) {
        long done, shown;
        @Override public int read(byte[] b, int off, int len) throws IOException {
          int k = super.read(b, off, len);
          if (k > 0) {
            done += k;
            if (done - shown > (256 << 10)) {
              shown = done;
              note.say("Scarico l'elenco dei giochi: " + mb(done) + (total > 0 ? " di " + mb(total) : ""));
            }
          }
          return k;
        }
      };
    }
    try {
      Crypto.Header h = Crypto.readHeader(raw);
      if (h.kind != 1) throw new IOException("il file indice non è un indice");
      if (note != null && !r.j.has("enc")) note.say("Controllo la password…");
      Crypto.Keys k = r.keys(h);
      JSONObject idx = new JSONObject(new String(readAll(new GZIPInputStream(new Crypto.DecryptStream(raw, h, k))), "UTF-8"));
      JSONObject info = idx.getJSONObject("repo");
      r.j.put("id", info.getString("id"));
      if (!r.j.has("name") || r.j.optString("name").isEmpty()) r.j.put("name", info.optString("name"));
      r.j.put("stamp", stamp).put("checked", System.currentTimeMillis());
      writeFile(new File(stateDir(c), info.getString("id") + ".json"), idx.toString().getBytes("UTF-8"));
      return idx;
    } finally {
      raw.close();
    }
  }

  static Map<String, JSONObject> packages(JSONObject idx) {
    Map<String, JSONObject> m = new LinkedHashMap<String, JSONObject>();
    JSONArray a = idx == null ? null : idx.optJSONArray("packages");
    for (int i = 0; a != null && i < a.length(); i++) {
      JSONObject p = a.optJSONObject(i);
      m.put(p.optString("id"), p);
    }
    return m;
  }

  static String key(String repoId, String pkgId) { return repoId + "|" + pkgId; }

  static boolean installed(JSONObject state, String repoId, String pkgId) {
    JSONObject inst = state.optJSONObject("installed");
    if (inst == null) return false;
    JSONObject e = inst.optJSONObject(key(repoId, pkgId));
    if (e == null) return false;
    // files deleted behind our back (a disk removed): not installed
    JSONArray files = e.optJSONArray("files");
    for (int i = 0; files != null && i < files.length(); i++)
      if (!new File(files.optString(i)).exists()) return false;
    return true;
  }

  // ================================================================ game list (playlist + thumbnails)

  static String playlistName(Map<String, String> cfg) {
    String n = cfg.get("menu_startup_playlist");
    return n != null && n.endsWith(".lpl") ? n : "Repository.lpl";
  }

  /**
   * Rewrites the repositories' game list (one playlist, every repository) and missing thumbnails.
   * Returns true when the list file changed.
   */
  static boolean writePlaylists(Context c) {
    Map<String, String> cfg = config(c);
    JSONObject state = loadState(c);
    File plDir = folder(c, cfg, "playlist_directory", "playlists");
    File thDir = folder(c, cfg, "thumbnails_directory", "thumbnails");
    File cores = coresDir(c, cfg);
    List<JSONObject> items = new ArrayList<JSONObject>();
    try {
      for (Repo r : repos(state)) {
        JSONObject idx = index(c, r.id());
        for (JSONObject p : packages(idx).values()) {
          if (!"game".equals(p.optString("kind"))) continue;
          String main = p.optString("main");
          String path = installed(state, r.id(), p.optString("id"))
              ? state.getJSONObject("installed").getJSONObject(key(r.id(), p.optString("id"))).optString("main")
              : PREFIX + r.id() + "/" + main;
          String core = p.optString("core");
          JSONObject it = new JSONObject()
              .put("path", path)
              .put("label", p.optString("label"))
              .put("core_path", new File(cores, core + "_libretro_android.so").getAbsolutePath())
              .put("core_name", p.optString("core_name"))
              .put("crc32", "DETECT")
              .put("db_name", p.optString("db"));
          items.add(it);
        }
      }
      Collections.sort(items, new Comparator<JSONObject>() {
        @Override public int compare(JSONObject a, JSONObject b) {
          return a.optString("label").compareToIgnoreCase(b.optString("label"));
        }
      });
      JSONArray arr = new JSONArray();
      for (JSONObject it : items) arr.put(it);
      File pl = new File(plDir, playlistName(cfg));
      // the RetroFire stick's own list is not ours to replace
      if (pl.isFile() && !readFile(pl).contains("\"retrofire_repo\"") && hasDiskConfig(c)) {
        Log.i(TAG, "repos: " + pl + " belongs to the stick, list written as Repository.lpl");
        pl = new File(plDir, "Repository.lpl");
      }
      // no games: the list stays, empty (it is the home screen with the side menu)
      JSONObject data = new JSONObject()
          .put("version", "1.5")
          .put("retrofire_repo", true)
          .put("default_core_path", "")
          .put("default_core_name", "")
          .put("label_display_mode", 0)
          .put("right_thumbnail_mode", 0)
          .put("left_thumbnail_mode", 0)
          .put("sort_mode", 0)
          .put("items", arr);
      String text = data.toString(2);
      if (pl.isFile() && readFile(pl).equals(text)) return false;
      writeFile(pl, text.getBytes("UTF-8"));
      return true;
    } catch (Exception e) {
      Log.w(TAG, "repos: game list not written: " + e);
      return false;
    }
  }

  /** Box art and screenshot of every game, those still missing; slow the first time (JPEG to PNG). */
  static void writeAllThumbs(Context c) {
    File thDir = folder(c, config(c), "thumbnails_directory", "thumbnails");
    JSONObject state = loadState(c);
    for (Repo r : repos(state))
      for (JSONObject p : packages(index(c, r.id())).values())
        if ("game".equals(p.optString("kind"))) writeThumbs(thDir, p, p.optString("main"));
  }

  static final String BAD = "[&*/:`<>?\\\\|\"]";

  static void writeThumbs(File thDir, JSONObject p, String main) {
    JSONObject t = p.optJSONObject("thumbs");
    if (t == null) return;
    String db = p.optString("thumb_db");
    String stem = new File(main).getName();
    int dot = stem.lastIndexOf('.');
    if (dot > 0) stem = stem.substring(0, dot);
    String[][] kinds = { { "boxart", "Named_Boxarts" }, { "snap", "Named_Snaps" } };
    for (String[] k : kinds) {
      String b64 = t.optString(k[0]);
      if (b64.isEmpty()) continue;
      File dir = new File(new File(thDir, db), k[1]);
      String[] names = { stem.replaceAll(BAD, "_") };
      for (String n : names) {
        File f = new File(dir, n + ".png");
        if (f.isFile()) continue;
        try {
          byte[] jpg = Base64.decode(b64, Base64.DEFAULT);
          Bitmap bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
          if (bm == null) continue;
          dir.mkdirs();
          File tmp = new File(f.getPath() + ".tmp");
          OutputStream o = new FileOutputStream(tmp);
          try { bm.compress(Bitmap.CompressFormat.PNG, 100, o); } finally { o.close(); }
          tmp.renameTo(f);
          bm.recycle();
        } catch (Exception e) {
          Log.w(TAG, "repos: thumbnail " + f + ": " + e);
        }
      }
    }
  }

  // ================================================================ native side

  /** Loads content (or, with an empty content, only reloads the game lists) on the native side. */
  static void start(Activity a, String content, String core) {
    try {
      a.getClass().getMethod("repoStart", String.class, String.class).invoke(a, content, core);
    } catch (Exception e) {
      Log.w(TAG, "repos: repoStart: " + e);
    }
  }

  // ================================================================ entry points

  /** At app start: refreshes old indexes in the background and rewrites the game list. */
  static volatile boolean refreshing;

  /**
   * At app start, and then every INDEX_MAX_AGE while the app runs: asks each repository whether
   * its index changed (MEGA: size and date of index.rfi, a small request) and downloads it only
   * then. Without network the request just fails, quietly: the downloaded games do not need it.
   * The game list is rewritten, and the menu reloaded, only when something changed.
   */
  public static void onStart(final Activity a) {
    if (refreshing) return;
    refreshing = true;
    // No screen opens by itself here: a dialog shown while RetroArch takes the window at start
    // stays drawn without focus (keys go to the grid below). The grid is the home screen; an
    // empty one has an 'Aggiungi repository' button, and its side menu has 'Repository'.
    Thread t = new Thread(new Runnable() {
      @Override public void run() {
        boolean first = true;
        while (true) {
          boolean changed = refreshDue(a, first);
          changed |= writePlaylists(a);
          if (changed) start(a, "", "");   // the menu reloads the game lists
          writeAllThumbs(a);
          first = false;
          try { Thread.sleep(INDEX_MAX_AGE); } catch (InterruptedException e) { return; }
        }
      }
    }, "repo-refresh");
    t.setDaemon(true);
    t.setPriority(Thread.MIN_PRIORITY);
    t.start();
  }

  /** Refreshes the indexes not checked for INDEX_MAX_AGE; true when one of them changed. */
  static boolean refreshDue(Context c, boolean atStart) {
    JSONObject state = loadState(c);
    boolean changed = false, checked = false;
    for (Repo r : repos(state)) {
      if (System.currentTimeMillis() - r.j.optLong("checked") < INDEX_MAX_AGE && index(c, r.id()) != null) continue;
      String before = r.j.optString("stamp");
      try {
        refreshIndex(c, r, false);
        checked = true;
        changed |= !before.equals(r.j.optString("stamp"));
      } catch (Exception e) {
        Log.i(TAG, "repos: " + r.name() + " not checked: " + e.getMessage());
      }
    }
    if (checked) {
      // keep the newest state of the installed games (a download may have finished meanwhile)
      JSONObject now = loadState(c);
      try { now.put("repos", state.getJSONArray("repos")); } catch (org.json.JSONException e) { /* no */ }
      saveState(c, now);
    }
    return changed;
  }

  static AlertDialog.Builder builder(Activity a) {
    return new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
  }

  static void toast(final Activity a, final String text) {
    a.runOnUiThread(new Runnable() {
      @Override public void run() { Toast.makeText(a, text, Toast.LENGTH_LONG).show(); }
    });
  }

  static String mb(long bytes) {
    if (bytes >= (10L << 30)) return String.format(Locale.ITALY, "%.0f GB", bytes / 1073741824.0);
    if (bytes >= (1L << 30)) return String.format(Locale.ITALY, "%.1f GB", bytes / 1073741824.0);
    if (bytes >= (10L << 20)) return String.format(Locale.ITALY, "%.0f MB", bytes / 1048576.0);
    return String.format(Locale.ITALY, "%.1f MB", bytes / 1048576.0);
  }

  static String duration(long seconds) {
    if (seconds < 60) return seconds + " s";
    if (seconds < 3600) return (seconds / 60) + " min " + (seconds % 60) + " s";
    return (seconds / 3600) + " h " + ((seconds % 3600) / 60) + " min";
  }

  /** The "Repository" screen: the repositories, add one, update the lists. */
  public static void open(final Activity a) {
    a.runOnUiThread(new Runnable() {
      @Override public void run() { showManage(a); }
    });
  }

  static void showManage(final Activity a) {
    final JSONObject state = loadState(a);
    final List<Repo> list = repos(state);
    List<String> rows = new ArrayList<String>();
    for (Repo r : list) {
      int games = 0, have = 0;
      for (JSONObject p : packages(index(a, r.id())).values()) {
        if (!"game".equals(p.optString("kind"))) continue;
        games++;
        if (installed(state, r.id(), p.optString("id"))) have++;
      }
      rows.add(r.name() + "  ·  " + games + " giochi, " + have + " scaricati");
    }
    rows.add("+ Aggiungi repository (con il telefono)");
    rows.add("Aggiorna gli elenchi");
    rows.add("Spazio: " + mb(usedBytes(state)) + " scaricati, liberi " + mb(contentRoot(a).getUsableSpace())
        + (contentRoot(a).equals(home(a)) ? " (memoria interna)" : " (disco USB)"));
    builder(a).setTitle("Repository")
        .setItems(rows.toArray(new String[0]), new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface d, int which) {
            if (which < list.size()) showRepo(a, list.get(which));
            else if (which == list.size()) addWithPhone(a);
            else if (which == list.size() + 1) refreshAll(a);
          }
        })
        .setNegativeButton("Chiudi", null)
        .show();
  }

  static long usedBytes(JSONObject state) {
    long n = 0;
    JSONObject inst = state.optJSONObject("installed");
    if (inst == null) return 0;
    Iterator<String> it = inst.keys();
    while (it.hasNext()) n += inst.optJSONObject(it.next()).optLong("size");
    return n;
  }

  static void showRepo(final Activity a, final Repo r) {
    String[] items = { "Aggiorna elenco", "Rimuovi repository e giochi scaricati" };
    builder(a).setTitle(r.name())
        .setItems(items, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface d, int which) {
            if (which == 0) refreshAll(a);
            else confirmRemove(a, r);
          }
        })
        .setNegativeButton("Indietro", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface d, int w) { showManage(a); }
        })
        .show();
  }

  static void confirmRemove(final Activity a, final Repo r) {
    builder(a).setTitle("Rimuovere " + r.name() + "?")
        .setMessage("Il repository sparisce dall'elenco e i suoi giochi scaricati vengono cancellati "
            + "(i salvataggi restano).")
        .setPositiveButton("Rimuovi", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface d, int w) {
            new Thread(new Runnable() {
              @Override public void run() {
                JSONObject state = loadState(a);
                JSONArray keep = new JSONArray();
                for (Repo x : repos(state)) if (!x.id().equals(r.id())) keep.put(x.j);
                try { state.put("repos", keep); } catch (org.json.JSONException e) { /* no */ }
                JSONObject inst = state.optJSONObject("installed");
                List<String> gone = new ArrayList<String>();
                Iterator<String> it = inst == null ? null : inst.keys();
                while (it != null && it.hasNext()) { String k = it.next(); if (k.startsWith(r.id() + "|")) gone.add(k); }
                for (String k : gone) uninstall(state, k);
                new File(stateDir(a), r.id() + ".json").delete();
                saveState(a, state);
                writePlaylists(a);
                start(a, "", "");
                toast(a, r.name() + " rimosso");
              }
            }).start();
          }
        })
        .setNegativeButton("Annulla", null)
        .show();
  }

  static void refreshAll(final Activity a) {
    toast(a, "Aggiorno gli elenchi…");
    new Thread(new Runnable() {
      @Override public void run() {
        JSONObject state = loadState(a);
        StringBuilder errors = new StringBuilder();
        for (Repo r : repos(state)) {
          try { refreshIndex(a, r, false); }
          catch (Exception e) { errors.append(r.name()).append(": ").append(e.getMessage()).append('\n'); }
        }
        saveState(a, state);
        writePlaylists(a);
        start(a, "", "");
        toast(a, errors.length() == 0 ? "Elenchi aggiornati" : errors.toString().trim());
      }
    }).start();
  }

  // ================================================================ add a repository with the phone

  static String deviceName(Context c) {
    String n = null;
    try { n = Settings.Global.getString(c.getContentResolver(), "device_name"); } catch (Exception e) { /* none */ }
    return n != null && !n.isEmpty() ? n : Build.MODEL;
  }

  static Bitmap qr(String text, int px) {
    QrCode q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
    int n = q.size + 8;
    int scale = Math.max(1, px / n);
    Bitmap bm = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.RGB_565);
    for (int y = 0; y < n; y++)
      for (int x = 0; x < n; x++) {
        int color = q.getModule(x - 4, y - 4) ? Color.BLACK : Color.WHITE;
        for (int dy = 0; dy < scale; dy++)
          for (int dx = 0; dx < scale; dx++) bm.setPixel(x * scale + dx, y * scale + dy, color);
      }
    return bm;
  }

  static void addWithPhone(final Activity a) {
    final Pkarr link = new Pkarr();
    String url = link.pageUrl(deviceName(a));
    float dp = a.getResources().getDisplayMetrics().density;
    LinearLayout box = new LinearLayout(a);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    int pad = (int) (16 * dp);
    box.setPadding(pad, pad, pad, pad);
    ImageView img = new ImageView(a);
    img.setImageBitmap(qr(url, (int) (260 * dp)));
    box.addView(img);
    final TextView note = new TextView(a);
    note.setTextSize(16f);
    note.setGravity(Gravity.CENTER_HORIZONTAL);
    note.setPadding(0, pad, 0, 0);
    note.setText("Inquadra il codice con il telefono: si apre una pagina dove scegli o scrivi il "
        + "repository (link e password). Quando premi «Invia alla TV» la TV lo aggiunge da sola.\n\nIn attesa…");
    box.addView(note);
    final boolean[] open = { true };
    final AlertDialog d = builder(a).setTitle("Aggiungi repository").setView(box)
        .setNegativeButton("Annulla", null).create();
    d.setOnDismissListener(new DialogInterface.OnDismissListener() {
      @Override public void onDismiss(DialogInterface di) { open[0] = false; }
    });
    d.show();
    new Thread(new Runnable() {
      @Override public void run() {
        long until = System.currentTimeMillis() + 15 * 60_000L;
        while (open[0] && System.currentTimeMillis() < until) {
          JSONObject got = link.poll();
          if (got != null && open[0]) {
            open[0] = false;
            final AlertDialog[] busy = new AlertDialog[1];
            final TextView step = new TextView(a);
            step.setTextSize(17f);
            int p2 = (int) (24 * a.getResources().getDisplayMetrics().density);
            step.setPadding(p2, p2, p2, p2);
            step.setText("Ricevuto dal telefono.");
            a.runOnUiThread(new Runnable() {
              @Override public void run() {
                try { d.dismiss(); } catch (Exception e) { /* gone */ }
                busy[0] = builder(a).setTitle("Aggiungo il repository").setView(step).setCancelable(false).create();
                busy[0].show();
              }
            });
            Note note = new Note() {
              @Override public void say(final String text) {
                a.runOnUiThread(new Runnable() { @Override public void run() { step.setText(text); } });
              }
            };
            final String result = addRepos(a, got.optJSONArray("repos"), note);
            note.say("Preparo l'elenco dei giochi…");
            writePlaylists(a);
            start(a, "", "");   // the grid (always the home screen) reloads with the new games
            a.runOnUiThread(new Runnable() {
              @Override public void run() {
                try { if (busy[0] != null) busy[0].dismiss(); } catch (Exception e) { /* gone */ }
                Toast.makeText(a, result, Toast.LENGTH_LONG).show();
              }
            });
            writeAllThumbs(a);
            return;
          }
          try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
      }
    }, "repo-add").start();
  }

  /** Adds (or updates) the repositories received; returns a message for the user. */
  static String addRepos(Activity a, JSONArray list) {
    return addRepos(a, list, null);
  }

  static String addRepos(Activity a, JSONArray list, Note note) {
    if (list == null || list.length() == 0) return "Non è arrivato nessun repository.";
    JSONObject state = loadState(a);
    StringBuilder msg = new StringBuilder();
    for (int i = 0; i < list.length(); i++) {
      JSONObject in = list.optJSONObject(i);
      if (in == null) continue;
      try {
        JSONObject j = new JSONObject().put("url", in.optString("url").trim())
            .put("password", in.optString("password")).put("name", in.optString("name").trim());
        Repo r = new Repo(j);
        JSONObject idx = refreshIndex(a, r, true, note);
        JSONArray all = state.getJSONArray("repos");
        JSONArray keep = new JSONArray();
        for (int k = 0; k < all.length(); k++)
          if (!all.getJSONObject(k).optString("id").equals(r.id())) keep.put(all.getJSONObject(k));
        keep.put(r.j);
        state.put("repos", keep);
        int games = 0;
        for (JSONObject p : packages(idx).values()) if ("game".equals(p.optString("kind"))) games++;
        msg.append("Aggiunto ").append(r.name()).append(": ").append(games).append(" giochi.\n");
      } catch (Exception e) {
        msg.append(in.optString("name", in.optString("url"))).append(": ").append(e.getMessage()).append('\n');
      }
    }
    saveState(a, state);
    return msg.toString().trim();
  }

  // ================================================================ start a game that is not downloaded

  /** What a download needs: the packages, and the games to delete first to make room. */
  static final class Plan {
    static final class Item {
      final String repo; final JSONObject p;
      Item(String repo, JSONObject p) { this.repo = repo; this.p = p; }
      String id() { return p.optString("id"); }
      boolean isGame() { return "game".equals(p.optString("kind")); }
    }
    final List<Item> items;
    final Item game;
    final String core;
    final List<String> remove = new ArrayList<String>();
    Plan(List<Item> items, Item game, String core) { this.items = items; this.game = game; this.core = core; }
    long download() { long n = 0; for (Item i : items) n += i.p.optLong("blob_size"); return n; }
  }

  public static void launch(final Activity a, final String content, final String core, final String label) {
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          final Plan plan = plan(a, content, core);
          a.runOnUiThread(new Runnable() {
            @Override public void run() { showPlan(a, plan, label); }
          });
        } catch (final Exception e) {
          a.runOnUiThread(new Runnable() {
            @Override public void run() {
              builder(a).setTitle(label).setMessage("Non riesco a preparare il download: " + e.getMessage())
                  .setPositiveButton("OK", null).show();
            }
          });
        }
      }
    }, "repo-plan").start();
  }

  static Plan plan(Context c, String content, String core) throws IOException {
    if (!content.startsWith(PREFIX)) throw new IOException("non è un gioco di un repository");
    String rest = content.substring(PREFIX.length());
    int slash = rest.indexOf('/');
    String repoId = rest.substring(0, slash), main = rest.substring(slash + 1);
    JSONObject state = loadState(c);
    Map<String, JSONObject> pk = packages(index(c, repoId));
    JSONObject game = null;
    for (JSONObject p : pk.values()) if (main.equals(p.optString("main"))) game = p;
    if (game == null) throw new IOException("il gioco non è più nel repository: aggiorna gli elenchi");
    List<Plan.Item> items = new ArrayList<Plan.Item>();
    Set<String> seen = new HashSet<String>();
    addWithDeps(state, repoId, pk, game, items, seen);
    Plan.Item g = null;
    for (Plan.Item i : items) if (i.isGame()) g = i;
    if (g == null) g = new Plan.Item(repoId, game);   // already installed: only deps were missing
    return new Plan(items, g, core);
  }

  static void addWithDeps(JSONObject state, String repo, Map<String, JSONObject> pk, JSONObject p,
                          List<Plan.Item> out, Set<String> seen) {
    if (!seen.add(p.optString("id"))) return;
    JSONArray deps = p.optJSONArray("deps");
    for (int i = 0; deps != null && i < deps.length(); i++) {
      JSONObject d = pk.get(deps.optString(i));
      if (d != null) addWithDeps(state, repo, pk, d, out, seen);
    }
    if (!installed(state, repo, p.optString("id"))) out.add(new Plan.Item(repo, p));
  }

  /** Bytes needed on the game disk (games) and on internal storage (the rest). */
  static long[] need(Context c, Plan plan) {
    long game = 0, internal = 0;
    for (Plan.Item i : plan.items) {
      if (i.isGame()) game += i.p.optLong("size");
      else internal += i.p.optLong("size");
    }
    return new long[] { game, internal };
  }

  static void showPlan(final Activity a, final Plan plan, final String label) {
    if (plan.items.isEmpty()) {   // already there (the list was not reloaded yet)
      JSONObject state = loadState(a);
      JSONObject e = state.optJSONObject("installed") == null ? null
          : state.optJSONObject("installed").optJSONObject(key(plan.game.repo, plan.game.id()));
      if (e != null) { start(a, e.optString("main"), plan.core); return; }
    }
    final JSONObject state = loadState(a);
    Repo repo = repoById(state, plan.game.repo);
    StringBuilder m = new StringBuilder();
    String info = plan.game.p.optString("info");
    if (!info.isEmpty()) m.append(info).append("\n\n");
    m.append("Da scaricare:\n");
    for (Plan.Item i : plan.items) {
      String kind = i.isGame() ? "gioco" : "core".equals(i.p.optString("kind")) ? "emulatore"
          : "app".equals(i.p.optString("kind")) ? "configurazione" : "BIOS";
      m.append("  • ").append(i.p.optString("label")).append(" (").append(kind).append("), ")
          .append(mb(i.p.optLong("blob_size"))).append('\n');
    }
    long total = plan.download();
    long speed = Math.max(256 * 1024, state.optLong("speed", 2L << 20));
    m.append("\nTotale ").append(mb(total)).append(" da ").append(repo != null ? repo.name() : "repository")
        .append(", circa ").append(duration(total / speed)).append('\n');
    File root = contentRoot(a);
    m.append("Il gioco va ").append(root.equals(home(a)) ? "nella memoria interna" : "sul disco USB")
        .append(" (liberi ").append(mb(root.getUsableSpace())).append(").");
    final long[] need = need(a, plan);
    final long shortGame = need[0] + MARGIN - root.getUsableSpace();
    final long shortInternal = need[1] + MARGIN - home(a).getUsableSpace()
        - (root.equals(home(a)) ? Math.max(0, need[0]) : 0);
    final boolean tight = shortGame > 0 || shortInternal > 0;
    if (tight) m.append("\n\nNon c'è abbastanza spazio: scegli i giochi da cancellare.");
    builder(a).setTitle(label).setMessage(m.toString())
        .setPositiveButton(tight ? "Fai spazio" : "Scarica", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface d, int w) {
            if (tight) chooseRemovals(a, plan, label, Math.max(shortGame, 0), Math.max(shortInternal, 0));
            else download(a, plan);
          }
        })
        .setNegativeButton("Annulla", null)
        .show();
  }

  // ---------------------------------------------------------------- making room

  static final class Candidate {
    final String key, label; final long bytes; final int rank; final boolean onGameDisk;
    Candidate(String key, String label, long bytes, int rank, boolean onGameDisk) {
      this.key = key; this.label = label; this.bytes = bytes; this.rank = rank; this.onGameDisk = onGameDisk;
    }
  }

  /** Position of each content path in the play history (0 = last played); absent = not played lately. */
  static Map<String, Integer> history(Context c) {
    Map<String, Integer> out = new HashMap<String, Integer>();
    String path = config(c).get("content_history_path");
    if (path == null) return out;
    try {
      JSONArray items = new JSONObject(readFile(new File(path))).optJSONArray("items");
      for (int i = 0; items != null && i < items.length(); i++) {
        String p = items.optJSONObject(i).optString("path");
        if (!out.containsKey(p)) out.put(p, i);
      }
    } catch (Exception e) { /* no history yet */ }
    return out;
  }

  static List<Candidate> candidates(Context c, Plan plan) {
    JSONObject state = loadState(c);
    Map<String, Integer> hist = history(c);
    List<Candidate> out = new ArrayList<Candidate>();
    JSONObject inst = state.optJSONObject("installed");
    String root = contentRoot(c).getAbsolutePath();
    Iterator<String> it = inst == null ? null : inst.keys();
    while (it != null && it.hasNext()) {
      String k = it.next();
      JSONObject e = inst.optJSONObject(k);
      if (!"game".equals(e.optString("kind")) || k.equals(key(plan.game.repo, plan.game.id()))) continue;
      Integer rank = hist.get(e.optString("main"));
      out.add(new Candidate(k, e.optString("label"), e.optLong("size"), rank == null ? Integer.MAX_VALUE : rank,
          e.optString("main").startsWith(root)));
    }
    // least recently played first, the bigger first among the never played
    Collections.sort(out, new Comparator<Candidate>() {
      @Override public int compare(Candidate x, Candidate y) {
        if (x.rank != y.rank) return x.rank > y.rank ? -1 : 1;
        return x.bytes > y.bytes ? -1 : x.bytes < y.bytes ? 1 : 0;
      }
    });
    return out;
  }

  static void chooseRemovals(final Activity a, final Plan plan, final String label, final long shortGame, final long shortInternal) {
    final List<Candidate> cand = candidates(a, plan);
    if (cand.isEmpty()) {
      builder(a).setTitle(label).setMessage("Non c'è spazio e non ci sono giochi scaricati da cancellare. "
          + "Libera spazio sulla Fire TV o collega un disco USB.").setPositiveButton("OK", null).show();
      return;
    }
    final boolean[] checked = new boolean[cand.size()];
    long freed = 0;
    for (int i = 0; i < cand.size() && freed < shortGame + shortInternal; i++) { checked[i] = true; freed += cand.get(i).bytes; }
    String[] rows = new String[cand.size()];
    for (int i = 0; i < cand.size(); i++) {
      Candidate x = cand.get(i);
      rows[i] = x.label + "  ·  " + mb(x.bytes) + "  ·  "
          + (x.rank == Integer.MAX_VALUE ? "non giocato di recente" : x.rank == 0 ? "l'ultimo giocato" : "giocato " + (x.rank + 1) + "°");
    }
    builder(a).setTitle("Da cancellare (servono " + mb(shortGame + shortInternal) + ")")
        .setMultiChoiceItems(rows, checked, new DialogInterface.OnMultiChoiceClickListener() {
          @Override public void onClick(DialogInterface d, int which, boolean on) { checked[which] = on; }
        })
        .setPositiveButton("Cancella e scarica", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface d, int w) {
            long sum = 0;
            plan.remove.clear();
            for (int i = 0; i < cand.size(); i++) if (checked[i]) { sum += cand.get(i).bytes; plan.remove.add(cand.get(i).key); }
            if (sum < shortGame + shortInternal) {
              toast(a, "Non basta: mancano " + mb(shortGame + shortInternal - sum) + ". Scegli altri giochi.");
              chooseRemovals(a, plan, label, shortGame, shortInternal);
              return;
            }
            download(a, plan);
          }
        })
        .setNegativeButton("Annulla", null)
        .show();
  }

  static void uninstall(JSONObject state, String k) {
    JSONObject inst = state.optJSONObject("installed");
    JSONObject e = inst == null ? null : inst.optJSONObject(k);
    if (e == null) return;
    JSONArray files = e.optJSONArray("files");
    for (int i = 0; files != null && i < files.length(); i++) {
      File f = new File(files.optString(i));
      f.delete();
      File dir = f.getParentFile();   // empty folders of the game go too
      while (dir != null && dir.getName().length() > 0 && dir.list() != null && dir.list().length == 0 && dir.delete())
        dir = dir.getParentFile();
    }
    inst.remove(k);
  }

  // ---------------------------------------------------------------- download and install

  static final class Counting extends FilterInputStream {
    long count;
    final boolean[] cancel;
    Counting(InputStream in, boolean[] cancel) { super(in); this.cancel = cancel; }
    @Override public int read() throws IOException {
      int b = super.read();
      if (b >= 0) count++;
      return b;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (cancel[0]) throw new IOException("annullato");
      int k = super.read(b, off, len);
      if (k > 0) count += k;
      return k;
    }
  }

  /** Where a file of a package goes. */
  static File target(Context c, Map<String, String> cfg, File contentRoot, String path) {
    if (path.startsWith("cores/")) {
      String name = path.substring(6);
      if (name.endsWith(".info")) {
        String v = cfg.get("libretro_info_path");
        File d = v != null && !v.isEmpty() && !v.equals("default") ? new File(v) : new File(c.getApplicationInfo().dataDir, "info");
        return new File(d, name);
      }
      return new File(coresDir(c, cfg), name);
    }
    if (path.startsWith("roms/") || path.startsWith("disks/")) return new File(contentRoot, path);
    if (path.startsWith("bios/")) return new File(folder(c, cfg, "system_directory", "bios"), path.substring(5));
    if (path.startsWith("config/remaps/"))
      return new File(folder(c, cfg, "input_remapping_directory", "config/remaps"), path.substring(14));
    if (path.startsWith("config/")) return new File(folder(c, cfg, "rgui_config_directory", "config"), path.substring(7));
    return new File(home(c), path);   // retroarch.cfg, autoconfig/...
  }

  static void download(final Activity a, final Plan plan) {
    float dp = a.getResources().getDisplayMetrics().density;
    final TextView note = new TextView(a);
    note.setTextSize(16f);
    int pad = (int) (24 * dp);
    note.setPadding(pad, pad / 2, pad, pad / 3);
    note.setText("Preparo…");
    final boolean[] cancel = { false };
    final AlertDialog d = builder(a).setTitle(plan.game != null ? plan.game.p.optString("label") : "RetroFire")
        .setView(note).setCancelable(false)
        .setNegativeButton("Annulla", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface di, int w) { cancel[0] = true; }
        }).create();
    d.show();
    new Thread(new Runnable() {
      @Override public void run() {
        String error = null;
        JSONObject state = loadState(a);
        try {
          for (String k : plan.remove) uninstall(state, k);
          saveState(a, state);
          Map<String, String> cfg = config(a);
          File root = contentRoot(a);
          long total = plan.download(), before = 0, t0 = System.currentTimeMillis();
          Map<String, Map<String, Source.Remote>> listings = new HashMap<String, Map<String, Source.Remote>>();
          for (int n = 0; n < plan.items.size(); n++) {
            Plan.Item item = plan.items.get(n);
            Repo repo = repoById(state, item.repo);
            if (repo == null) throw new IOException("repository rimosso");
            Source src = Source.of(repo.url());
            Map<String, Source.Remote> listing = listings.get(item.repo);
            if (listing == null) { listing = src.list(); listings.put(item.repo, listing); }
            Counting in = new Counting(src.open(item.p.optString("blob"), listing), cancel);
            List<String> written = new ArrayList<String>();
            try {
              Crypto.Header h = Crypto.readHeader(in);
              ZipInputStream z = new ZipInputStream(new Crypto.DecryptStream(in, h, repo.keys(h)));
              ZipEntry e;
              byte[] buf = new byte[1 << 16];
              long lastShow = 0;
              while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || e.getName().contains("..")) continue;
                File out = target(a, cfg, root, e.getName());
                out.getParentFile().mkdirs();
                File tmp = new File(out.getPath() + ".part");
                OutputStream o = new FileOutputStream(tmp);
                try {
                  int k;
                  while ((k = z.read(buf)) > 0) {
                    o.write(buf, 0, k);
                    long now = System.currentTimeMillis();
                    if (now - lastShow > 500) {
                      lastShow = now;
                      long done = before + in.count;
                      double secs = Math.max(0.5, (now - t0) / 1000.0);
                      long speed = (long) (done / secs);
                      final String text = "Da " + repo.name() + "\n"
                          + "Pacchetto " + (n + 1) + " di " + plan.items.size() + ": " + item.p.optString("label") + "\n"
                          + mb(done) + " di " + mb(total) + "  ·  " + mb(speed) + "/s"
                          + (speed > 0 ? "  ·  mancano " + duration(Math.max(0, total - done) / Math.max(1, speed)) : "");
                      a.runOnUiThread(new Runnable() { @Override public void run() { note.setText(text); } });
                    }
                  }
                } finally {
                  o.close();
                }
                if (out.exists()) out.delete();
                if (!tmp.renameTo(out)) throw new IOException("non riesco a scrivere " + out);
                if (out.getName().endsWith(".so")) out.setExecutable(true, false);
                written.add(out.getAbsolutePath());
              }
              z.close();
            } catch (IOException ex) {
              for (String w : written) new File(w).delete();
              throw ex;
            }
            before += in.count;
            JSONObject rec = new JSONObject().put("kind", item.p.optString("kind")).put("label", item.p.optString("label"))
                .put("size", item.p.optLong("size")).put("at", System.currentTimeMillis())
                .put("files", new JSONArray(written));
            if (item.isGame()) rec.put("main", target(a, cfg, root, item.p.optString("main")).getAbsolutePath());
            state.getJSONObject("installed").put(key(item.repo, item.id()), rec);
            saveState(a, state);
          }
          long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
          if (total > (4L << 20)) state.put("speed", total / secs);
          saveState(a, state);
          writePlaylists(a);
        } catch (Source.QuotaException e) {
          error = e.getMessage();
        } catch (Exception e) {
          error = cancel[0] ? "Download annullato" : (e.getMessage() != null ? e.getMessage() : e.toString());
          Log.w(TAG, "repos: download failed", e);
        }
        final String err = error;
        a.runOnUiThread(new Runnable() {
          @Override public void run() {
            try { d.dismiss(); } catch (Exception e) { /* gone */ }
            finished(a, plan, err);
          }
        });
      }
    }, "repo-download").start();
  }

  static void finished(final Activity a, final Plan plan, String error) {
    if (error != null) {
      builder(a).setTitle("Download non riuscito").setMessage(error).setPositiveButton("OK", null).show();
      return;
    }
    start(a, "", "");   // the game list shows it as downloaded
    if (plan.game == null) return;
    JSONObject state = loadState(a);
    JSONObject e = state.optJSONObject("installed") == null ? null
        : state.optJSONObject("installed").optJSONObject(key(plan.game.repo, plan.game.id()));
    if (e == null) return;
    final String main = e.optString("main");
    builder(a).setTitle(plan.game.p.optString("label"))
        .setMessage("Scaricato. Vuoi giocare adesso?")
        .setPositiveButton("Gioca", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface d, int w) { start(a, main, plan.core); }
        })
        .setNegativeButton("Più tardi", null)
        .show();
  }
}
