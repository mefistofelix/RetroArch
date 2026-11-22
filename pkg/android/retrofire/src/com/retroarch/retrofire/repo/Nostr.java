package com.retroarch.retrofire.repo;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocketFactory;

/**
 * Receives what the phone page (retrofire-pages/addrepo) sends: a Nostr event of kind 30078
 * tagged d=<topic>, content = base64url(iv 12 + AES-256-GCM(json, key, aad = topic)). Only the
 * relays are contacted; the TV never publishes anything. A minimal WebSocket client (RFC 6455:
 * text frames, ping, close) is enough for the request and its answers.
 */
public final class Nostr {
  public static final String[] RELAYS = {
      "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.nostr.band" };
  public static final String PAGE = "https://mefistofelix.github.io/retrofire-pages/addrepo/";

  public final String topic;
  public final byte[] key;

  public Nostr() {
    SecureRandom r = new SecureRandom();
    byte[] t = new byte[16];
    r.nextBytes(t);
    StringBuilder sb = new StringBuilder();
    for (byte b : t) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
    topic = sb.toString();
    key = new byte[32];
    r.nextBytes(key);
  }

  public String pageUrl(String deviceName) {
    return PAGE + "#t=" + topic + "&k=" + Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING)
        + "&d=" + android.net.Uri.encode(deviceName);
  }

  /** Asks every relay once; returns the decrypted payload of the newest event, or null. */
  public JSONObject poll() {
    JSONObject best = null;
    long bestTime = -1;
    for (String relay : RELAYS) {
      try {
        JSONObject[] got = ask(relay);
        for (JSONObject ev : got) {
          long t = ev.optLong("created_at");
          if (t <= bestTime) continue;
          JSONObject p = decrypt(ev.optString("content"));
          if (p != null) { best = p; bestTime = t; }
        }
      } catch (Exception e) {
        // a relay that does not answer: the others may
      }
    }
    return best;
  }

  JSONObject decrypt(String content) {
    try {
      byte[] raw = Base64.decode(content, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(raw, 0, 12)));
      c.updateAAD(topic.getBytes("UTF-8"));
      byte[] plain = c.doFinal(raw, 12, raw.length - 12);
      return new JSONObject(new String(plain, "UTF-8"));
    } catch (Exception e) {
      return null;   // not for us, or damaged
    }
  }

  JSONObject[] ask(String relay) throws Exception {
    URI u = new URI(relay);
    int port = u.getPort() > 0 ? u.getPort() : 443;
    Socket plain = new Socket();
    plain.connect(new InetSocketAddress(u.getHost(), port), 8000);
    // layered on a connected socket with the host name, so the TLS handshake carries it (SNI)
    Socket s = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, u.getHost(), port, true);
    try {
      s.setSoTimeout(8000);
      OutputStream o = s.getOutputStream();
      InputStream in = s.getInputStream();
      byte[] nonce = new byte[16];
      new SecureRandom().nextBytes(nonce);
      String req = "GET " + (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + " HTTP/1.1\r\n"
          + "Host: " + u.getHost() + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\n"
          + "Sec-WebSocket-Version: 13\r\nUser-Agent: RetroFire\r\n\r\n";
      o.write(req.getBytes("UTF-8"));
      o.flush();
      String status = readHeaders(in);
      if (!status.contains(" 101")) throw new IOException("relay: " + status);
      String sub = "rf" + topic.substring(0, 8);
      JSONObject filter = new JSONObject().put("kinds", new JSONArray().put(30078))
          .put("#d", new JSONArray().put(topic)).put("limit", 5);
      send(o, new JSONArray().put("REQ").put(sub).put(filter).toString(), 0x1);
      java.util.List<JSONObject> events = new java.util.ArrayList<JSONObject>();
      long until = System.currentTimeMillis() + 8000;
      while (System.currentTimeMillis() < until) {
        String msg = readText(in, o);
        if (msg == null) break;
        JSONArray a = new JSONArray(msg);
        String type = a.optString(0);
        if ("EVENT".equals(type)) events.add(a.getJSONObject(2));
        else if ("EOSE".equals(type) || "CLOSED".equals(type)) break;
      }
      try {
        send(o, new JSONArray().put("CLOSE").put(sub).toString(), 0x1);
        send(o, "", 0x8);
      } catch (IOException e) { /* closing anyway */ }
      return events.toArray(new JSONObject[0]);
    } finally {
      try { s.close(); } catch (IOException e) { /* gone */ }
    }
  }

  static String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c, state = 0;
    while ((c = in.read()) >= 0) {
      b.write(c);
      state = (c == '\r' || c == '\n') ? state + 1 : 0;
      if (state == 4) break;
      if (b.size() > 16384) throw new IOException("header too long");
    }
    String h = b.toString("UTF-8");
    int eol = h.indexOf('\r');
    return eol > 0 ? h.substring(0, eol) : h;
  }

  static void send(OutputStream o, String text, int opcode) throws IOException {
    byte[] data = text.getBytes("UTF-8");
    ByteArrayOutputStream f = new ByteArrayOutputStream();
    f.write(0x80 | opcode);
    if (data.length < 126) f.write(0x80 | data.length);
    else if (data.length < 65536) { f.write(0x80 | 126); f.write(data.length >>> 8); f.write(data.length); }
    else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : data.length >>> (8 * i)); }
    byte[] mask = new byte[4];
    new SecureRandom().nextBytes(mask);
    f.write(mask);
    for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i & 3]);
    o.write(f.toByteArray());
    o.flush();
  }

  /** Next text message (fragments joined); answers pings; null when the relay closes. */
  static String readText(InputStream in, OutputStream o) throws IOException {
    ByteArrayOutputStream msg = new ByteArrayOutputStream();
    while (true) {
      int b0 = in.read(), b1 = in.read();
      if (b0 < 0 || b1 < 0) return null;
      boolean fin = (b0 & 0x80) != 0;
      int op = b0 & 0x0f;
      long len = b1 & 0x7f;
      if (len == 126) len = (in.read() << 8) | in.read();
      else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
      byte[] mask = null;
      if ((b1 & 0x80) != 0) { mask = new byte[4]; Crypto.readFully(in, mask, 0, 4); }
      if (len > (8 << 20)) throw new IOException("frame too big");
      byte[] data = new byte[(int) len];
      if (Crypto.readFully(in, data, 0, data.length) < data.length) return null;
      if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
      if (op == 0x8) return null;
      if (op == 0x9) { pong(o, data); continue; }
      if (op == 0xA) continue;
      msg.write(data);
      if (fin) return msg.toString("UTF-8");
    }
  }

  static void pong(OutputStream o, byte[] data) throws IOException {
    ByteArrayOutputStream f = new ByteArrayOutputStream();
    f.write(0x8A);
    f.write(0x80 | Math.min(125, data.length));
    byte[] mask = new byte[4];
    f.write(mask);
    f.write(data, 0, Math.min(125, data.length));
    o.write(f.toByteArray());
    o.flush();
  }
}
