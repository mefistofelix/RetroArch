package com.retroarch.retrofire.repo;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The RFE1 container of RetroFire repositories (REPOS.md in the RetroFire project, written by
 * scripts/rfrepo.py): a 40-byte header, then AES-256-GCM chunks, the last one flagged in the
 * additional data. Keys: PBKDF2-HMAC-SHA256(password, salt, iterations, 64) = enc + names.
 */
public final class Crypto {
  public static final int HEADER = 40;

  private Crypto() {}

  public static final class Keys {
    public final byte[] salt, enc, names;
    public final int iterations;
    Keys(byte[] salt, int iterations, byte[] enc, byte[] names) {
      this.salt = salt; this.iterations = iterations; this.enc = enc; this.names = names;
    }
  }

  /** PBKDF2-HMAC-SHA256, written out: Android before 8.0 has only the SHA-1 variant. */
  public static Keys derive(String password, byte[] salt, int iterations) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(password.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] out = new byte[64];
    for (int block = 1; block <= 2; block++) {
      mac.update(salt);
      mac.update(new byte[] { 0, 0, 0, (byte) block });
      byte[] u = mac.doFinal();
      byte[] t = u.clone();
      for (int i = 1; i < iterations; i++) {
        u = mac.doFinal(u);
        for (int j = 0; j < t.length; j++) t[j] ^= u[j];
      }
      System.arraycopy(t, 0, out, (block - 1) * 32, 32);
    }
    return new Keys(salt, iterations, Arrays.copyOfRange(out, 0, 32), Arrays.copyOfRange(out, 32, 64));
  }

  public static final class Header {
    public final byte[] raw;
    public final int kind, iterations, chunk;
    public final byte[] salt, prefix;
    Header(byte[] raw) throws IOException {
      if (raw[0] != 'R' || raw[1] != 'F' || raw[2] != 'E' || raw[3] != '1' || raw[4] != 1)
        throw new IOException("non è un file di repository RetroFire");
      this.raw = raw;
      kind = raw[5];
      iterations = be32(raw, 6);
      salt = Arrays.copyOfRange(raw, 10, 26);
      prefix = Arrays.copyOfRange(raw, 26, 34);
      chunk = be32(raw, 34);
      if (chunk <= 0 || chunk > (16 << 20)) throw new IOException("blocchi non validi");
    }
  }

  static int be32(byte[] b, int o) {
    return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
  }

  public static Header readHeader(InputStream in) throws IOException {
    byte[] h = new byte[HEADER];
    readFully(in, h, 0, HEADER);
    return new Header(h);
  }

  static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
    int done = 0;
    while (done < len) {
      int k = in.read(b, off + done, len - done);
      if (k < 0) break;
      done += k;
    }
    return done;
  }

  /** Decrypts an RFE1 stream whose header has already been read. */
  public static final class DecryptStream extends InputStream {
    private final InputStream in;
    private final Header h;
    private final SecretKeySpec key;
    private final byte[] cur, next;
    private int curLen, nextLen, index;
    private byte[] plain = new byte[0];
    private int pos;
    private boolean done;

    public DecryptStream(InputStream in, Header h, Keys keys) throws IOException {
      this.in = in; this.h = h;
      this.key = new SecretKeySpec(keys.enc, "AES");
      cur = new byte[h.chunk + 16];
      next = new byte[h.chunk + 16];
      curLen = readFully(in, cur, 0, cur.length);
      nextLen = curLen == cur.length ? readFully(in, next, 0, next.length) : 0;
    }

    private boolean fill() throws IOException {
      if (done) return false;
      if (curLen < 16) throw new EOFException("file troncato");
      boolean last = nextLen == 0;
      try {
        byte[] nonce = new byte[12];
        System.arraycopy(h.prefix, 0, nonce, 0, 8);
        nonce[8] = (byte) (index >>> 24); nonce[9] = (byte) (index >>> 16);
        nonce[10] = (byte) (index >>> 8); nonce[11] = (byte) index;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        c.updateAAD(h.raw);
        c.updateAAD(new byte[] { (byte) (last ? 1 : 0) });
        plain = c.doFinal(cur, 0, curLen);
      } catch (GeneralSecurityException e) {
        throw new IOException("password errata o file danneggiato");
      }
      pos = 0;
      index++;
      if (last) {
        done = true;
      } else {
        System.arraycopy(next, 0, cur, 0, nextLen);
        curLen = nextLen;
        nextLen = curLen == cur.length ? readFully(in, next, 0, next.length) : 0;
      }
      return true;
    }

    @Override public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
      while (pos >= plain.length) {
        if (!fill()) return -1;
      }
      int k = Math.min(len, plain.length - pos);
      System.arraycopy(plain, pos, b, off, k);
      pos += k;
      return k;
    }

    @Override public void close() throws IOException { in.close(); }
  }
}
