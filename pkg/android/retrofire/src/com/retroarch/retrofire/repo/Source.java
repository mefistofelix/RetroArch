package com.retroarch.retrofire.repo;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Where a repository's files are: a MEGA public folder, or an HTTPS base address. */
public abstract class Source {
  public static final class Remote {
    public final String name; public final long size; public final long time;
    final String handle; final byte[] key, iv;
    Remote(String name, long size, long time, String handle, byte[] key, byte[] iv) {
      this.name = name; this.size = size; this.time = time; this.handle = handle; this.key = key; this.iv = iv;
    }
  }

  /** Thrown when the host refuses for a while (MEGA's transfer quota of free accounts). */
  public static final class QuotaException extends IOException {
    QuotaException(String m) { super(m); }
  }

  /** name -> file (name, size, change time); may be empty for sources that cannot list. */
  public abstract Map<String, Remote> list() throws IOException;
  /** The raw bytes of a file (already decrypted from MEGA's own layer). */
  public abstract InputStream open(String name, Map<String, Remote> listing) throws IOException;

  public static Source of(String url) throws IOException {
    if (url.contains("mega.nz/") || url.contains("mega.co.nz/")) return new Mega(url);
    if (url.startsWith("https://") || url.startsWith("http://")) return new Http(url);
    throw new IOException("indirizzo non riconosciuto: " + url);
  }

  static HttpURLConnection connect(String url, int timeout) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(timeout);
    c.setRequestProperty("User-Agent", "RetroFire");
    return c;
  }

  static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    byte[] buf = new byte[16384];
    int k;
    while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
    in.close();
    return b.toString("UTF-8");
  }

  // ---------------------------------------------------------------- plain HTTPS folder

  static final class Http extends Source {
    final String base;
    Http(String url) { base = url.endsWith("/") ? url : url + "/"; }

    @Override public Map<String, Remote> list() { return new HashMap<String, Remote>(); }

    @Override public InputStream open(String name, Map<String, Remote> listing) throws IOException {
      HttpURLConnection c = connect(base + Uri.encode(name), 60000);
      int code = c.getResponseCode();
      if (code == 429 || code == 509) throw new QuotaException("il server limita i download: riprova più tardi");
      if (code != 200) throw new IOException("HTTP " + code + " per " + name);
      return c.getInputStream();
    }
  }

  private static final class Uri {
    static String encode(String s) { return android.net.Uri.encode(s); }
  }

  // ---------------------------------------------------------------- MEGA public folder

  static final class Mega extends Source {
    final String folder;
    final byte[] master;
    static final SecureRandom RANDOM = new SecureRandom();

    Mega(String link) throws IOException {
      Matcher m = Pattern.compile("mega(?:\\.co)?\\.nz/folder/([^#/?]+)#([^/?]+)").matcher(link);
      if (!m.find()) m = Pattern.compile("mega(?:\\.co)?\\.nz/#F!([^!]+)!([^/?]+)").matcher(link);
      if (!m.find(0)) throw new IOException("link MEGA non valido (serve il link di una cartella con la chiave)");
      folder = m.group(1);
      master = b64(m.group(2));
      if (master.length != 16) throw new IOException("chiave della cartella MEGA non valida");
    }

    static byte[] b64(String s) {
      return Base64.decode(s.replace('-', '+').replace('_', '/'), Base64.NO_WRAP | Base64.NO_PADDING);
    }

    Object api(JSONObject cmd) throws IOException {
      IOException last = null;
      for (int attempt = 0; attempt < 6; attempt++) {
        try {
          HttpURLConnection c = connect("https://g.api.mega.co.nz/cs?id=" + RANDOM.nextInt(1 << 30) + "&n=" + folder, 60000);
          c.setRequestMethod("POST");
          c.setDoOutput(true);
          c.setRequestProperty("Content-Type", "application/json");
          OutputStream o = c.getOutputStream();
          o.write(new JSONArray().put(cmd).toString().getBytes("UTF-8"));
          o.close();
          String text = readAll(c.getInputStream()).trim();
          Object res = text.startsWith("[") ? new JSONArray(text).get(0) : Integer.valueOf(Integer.parseInt(text));
          if (res instanceof Integer) {
            int e = (Integer) res;
            if (e == -3 || e == -4) { last = new IOException("MEGA occupato (" + e + ")"); sleep(1000L << attempt); continue; }
            if (e == -17) throw new QuotaException("quota di traffico MEGA esaurita: riprova più tardi");
            if (e == -9 || e == -2) throw new IOException("cartella MEGA non trovata (" + e + ")");
            throw new IOException("errore MEGA " + e);
          }
          return res;
        } catch (org.json.JSONException e) {
          last = new IOException("risposta MEGA non valida");
        }
      }
      throw last;
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { /* go on */ } }

    @Override public Map<String, Remote> list() throws IOException {
      Map<String, Remote> out = new HashMap<String, Remote>();
      try {
        JSONObject res = (JSONObject) api(new JSONObject().put("a", "f").put("c", 1).put("ca", 1).put("r", 1));
        JSONArray nodes = res.getJSONArray("f");
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.DECRYPT_MODE, new SecretKeySpec(master, "AES"));
        // the shared folder: the node whose parent is not in the listing
        java.util.Set<String> handles = new java.util.HashSet<String>(), roots = new java.util.HashSet<String>();
        for (int i = 0; i < nodes.length(); i++) handles.add(nodes.getJSONObject(i).optString("h"));
        for (int i = 0; i < nodes.length(); i++)
          if (!handles.contains(nodes.getJSONObject(i).optString("p"))) roots.add(nodes.getJSONObject(i).optString("h"));
        for (int i = 0; i < nodes.length(); i++) {
          JSONObject n = nodes.getJSONObject(i);
          if (n.optInt("t", -1) != 0) continue;
          // "k" can hold several "<handle>:<key>" separated by "/": the shared folder's one is
          // encrypted with the link's key
          String piece = null;
          for (String part : n.optString("k").split("/")) {
            int colon = part.indexOf(':');
            if (colon > 0 && roots.contains(part.substring(0, colon))) piece = part.substring(colon + 1);
          }
          if (piece == null) continue;
          byte[] enc = b64(piece);
          if (enc.length != 32) continue;
          byte[] full = ecb.doFinal(enc);
          byte[] key = new byte[16];
          for (int j = 0; j < 16; j++) key[j] = (byte) (full[j] ^ full[j + 16]);
          byte[] iv = new byte[16];
          System.arraycopy(full, 16, iv, 0, 8);
          Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
          cbc.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
          byte[] attr = cbc.doFinal(b64(n.getString("a")));
          String a = new String(attr, "UTF-8");
          int end = a.indexOf('\0');
          if (end >= 0) a = a.substring(0, end);
          if (!a.startsWith("MEGA")) continue;
          String name = new JSONObject(a.substring(4)).getString("n");
          out.put(name, new Remote(name, n.optLong("s"), n.optLong("ts"), n.getString("h"), key, iv));
        }
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("cartella MEGA illeggibile: " + e.getMessage());
      }
      return out;
    }

    @Override public InputStream open(String name, Map<String, Remote> listing) throws IOException {
      if (listing == null || listing.isEmpty()) listing = list();
      Remote r = listing.get(name);
      if (r == null) throw new IOException("file mancante nel repository: " + name);
      String url;
      try {
        JSONObject res = (JSONObject) api(new JSONObject().put("a", "g").put("g", 1).put("ssl", 1).put("n", r.handle));
        url = res.getString("g");
      } catch (org.json.JSONException e) {
        throw new IOException("MEGA non dà l'indirizzo di " + name);
      }
      HttpURLConnection c = connect(url, 60000);
      int code = c.getResponseCode();
      if (code == 509) throw new QuotaException("quota di traffico MEGA esaurita: riprova più tardi");
      if (code != 200) throw new IOException("HTTP " + code + " da MEGA");
      try {
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.DECRYPT_MODE, new SecretKeySpec(r.key, "AES"), new IvParameterSpec(r.iv));
        return new CipherIn(c.getInputStream(), ctr);
      } catch (java.security.GeneralSecurityException e) {
        throw new IOException(e.toString());
      }
    }
  }

  /** Stream through a cipher in CTR mode (no padding, so update() keeps up byte for byte). */
  static final class CipherIn extends FilterInputStream {
    final Cipher c;
    CipherIn(InputStream in, Cipher c) { super(in); this.c = c; }
    @Override public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      int k = in.read(b, off, len);
      if (k <= 0) return k;
      byte[] out = c.update(b, off, k);
      if (out != null) System.arraycopy(out, 0, b, off, out.length);
      return out == null ? 0 : out.length;
    }
  }
}
