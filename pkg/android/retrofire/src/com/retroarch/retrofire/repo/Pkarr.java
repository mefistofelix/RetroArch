package com.retroarch.retrofire.repo;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Receives what the phone page (retrofire-pages/addrepo) sends, through pkarr: the signed DNS
 * records that iroh uses to find its nodes, kept by public relays (GET/PUT /<public key>).
 * The QR code carries an Ed25519 key seed and an AES key; the page signs a DNS packet whose TXT
 * record "_rf" holds the repositories (deflate-raw, then AES-256-GCM with the AES key, additional
 * data = the public key in z-base-32) and PUTs it to the relays. The TV only GETs it: plain
 * HTTPS, nothing kept open. The relays check the signature; the AES tag proves it came from
 * someone who saw the QR.
 */
public final class Pkarr {
  public static final String[] RELAYS = {
      "https://relay.pkarr.org", "https://dns.iroh.link/pkarr", "https://pkarr.pubky.app", "https://pkarr.pubky.org" };
  public static final String PAGE = "https://mefistofelix.github.io/retrofire-pages/addrepo/";

  public final byte[] seed = new byte[32];
  public final byte[] key = new byte[32];
  public final String zkey;

  public Pkarr() {
    SecureRandom r = new SecureRandom();
    r.nextBytes(seed);
    r.nextBytes(key);
    zkey = z32(Ed25519.publicKey(seed));
  }

  static String b64url(byte[] b) {
    return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
  }

  public String pageUrl(String deviceName) {
    return PAGE + "#s=" + b64url(seed) + "&k=" + b64url(key) + "&d=" + android.net.Uri.encode(deviceName);
  }

  static final String Z32 = "ybndrfg8ejkmcpqxot1uwisza345h769";

  static String z32(byte[] data) {
    StringBuilder out = new StringBuilder();
    int bits = 0, value = 0;
    for (byte b : data) {
      value = (value << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) { out.append(Z32.charAt((value >>> (bits - 5)) & 31)); bits -= 5; }
    }
    if (bits > 0) out.append(Z32.charAt((value << (5 - bits)) & 31));
    return out.toString();
  }

  /** Asks the relays in turn; the decrypted payload, or null when nothing has arrived yet. */
  public JSONObject poll() {
    for (String relay : RELAYS) {
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(relay + "/" + zkey).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "RetroFire");
        if (c.getResponseCode() != 200) { c.disconnect(); continue; }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int k;
        while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
        in.close();
        String txt = txtRecord(b.toByteArray());
        JSONObject p = txt == null ? null : decrypt(txt);
        if (p != null) return p;
      } catch (Exception e) {
        // this relay does not answer: the next one may
      }
    }
    return null;
  }

  /** The first TXT record of a signed packet (64 signature + 8 timestamp + DNS message). */
  static String txtRecord(byte[] body) throws Exception {
    if (body.length < 72 + 12) return null;
    byte[] p = Arrays.copyOfRange(body, 72, body.length);
    int answers = ((p[6] & 0xff) << 8) | (p[7] & 0xff);
    int i = 12;
    for (int n = 0; n < answers; n++) {
      while (true) {                       // the name: labels, or a pointer
        int len = p[i] & 0xff;
        if (len == 0) { i++; break; }
        if ((len & 0xc0) == 0xc0) { i += 2; break; }
        i += len + 1;
      }
      int type = ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff);
      int rdlen = ((p[i + 8] & 0xff) << 8) | (p[i + 9] & 0xff);
      int rd = i + 10;
      i = rd + rdlen;
      if (type != 16) continue;
      ByteArrayOutputStream txt = new ByteArrayOutputStream();
      for (int j = rd; j < rd + rdlen; ) {
        int len = p[j] & 0xff;
        txt.write(p, j + 1, len);
        j += 1 + len;
      }
      return txt.toString("UTF-8");
    }
    return null;
  }

  JSONObject decrypt(String content) {
    try {
      byte[] raw = Base64.decode(content, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(raw, 0, 12)));
      c.updateAAD(zkey.getBytes("UTF-8"));
      byte[] packed = c.doFinal(raw, 12, raw.length - 12);
      Inflater inf = new Inflater(true);
      inf.setInput(packed);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      while (!inf.finished()) {
        int k = inf.inflate(buf);
        if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
        out.write(buf, 0, k);
      }
      inf.end();
      return new JSONObject(out.toString("UTF-8"));
    } catch (Exception e) {
      return null;   // not for us, or damaged
    }
  }

  /** Ed25519 public key from a 32-byte seed (RFC 8032), with BigInteger: done once per QR. */
  static final class Ed25519 {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger D = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    static final BigInteger BX = new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202");
    static final BigInteger BY = new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960");

    /** Twisted Edwards addition (a = -1) in affine coordinates. */
    static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
      BigInteger x1 = p[0], y1 = p[1], x2 = q[0], y2 = q[1];
      BigInteger t = D.multiply(x1).multiply(x2).multiply(y1).multiply(y2).mod(P);
      BigInteger x3 = x1.multiply(y2).add(y1.multiply(x2)).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P);
      BigInteger y3 = y1.multiply(y2).add(x1.multiply(x2)).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P);
      return new BigInteger[] { x3, y3 };
    }

    static byte[] publicKey(byte[] seed) {
      try {
        byte[] h = MessageDigest.getInstance("SHA-512").digest(seed);
        byte[] a = Arrays.copyOf(h, 32);
        a[0] &= (byte) 248;
        a[31] &= 127;
        a[31] |= 64;
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) be[i] = a[31 - i];
        BigInteger s = new BigInteger(1, be);
        BigInteger[] r = { BigInteger.ZERO, BigInteger.ONE };
        BigInteger[] q = { BX, BY };
        for (int i = 0; i < s.bitLength(); i++) {
          if (s.testBit(i)) r = add(r, q);
          q = add(q, q);
        }
        byte[] out = new byte[32];
        byte[] y = r[1].toByteArray();   // big endian, maybe with a sign byte
        for (int i = 0; i < 32 && i < y.length; i++) out[i] = y[y.length - 1 - i];
        if (r[0].testBit(0)) out[31] |= (byte) 0x80;
        return out;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
