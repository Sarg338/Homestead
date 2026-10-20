/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

import javax.crypto.Cipher;

/**
 * The RSA key this app presents to adbd, generated and stored on the device.
 *
 * Two ADB-specific details:
 *  1. adbd treats the 20-byte token as an already-computed SHA-1 digest, so the signature
 *     is PKCS#1 v1.5 padding + SHA-1 DigestInfo + token, built by hand and run through raw
 *     RSA. Java's SHA1withRSA would hash the token again and be rejected.
 *  2. The public key uses adb's own struct (libcrypto_utils/android_pubkey.c), not X.509;
 *     see encodePublicKey().
 *
 * Storage is app-private; the manifest sets allowBackup="false" to keep the key on-device.
 */
final class AdbCrypto {

    private static final String DIR = "adb";
    private static final String PRIV = "adbkey";
    private static final String PUB = "adbkey.pub";

    private static final int KEY_BITS = 2048;
    private static final int MODULUS_BYTES = KEY_BITS / 8;              // 256
    private static final int MODULUS_WORDS = MODULUS_BYTES / 4;         // 64
    /** 4 + 4 + 256 + 256 + 4, the wire size of the adb RSAPublicKey struct. */
    private static final int PUBKEY_BLOB = 8 + 2 * MODULUS_BYTES + 4;
    static final int TOKEN_BYTES = 20;

    /** ASN.1 DigestInfo header for a SHA-1 hash, per PKCS#1 v1.5. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    /** 0x00 0x01 FF... 0x00 DigestInfo, sized so prefix + a 20-byte token is exactly 256. */
    private static final byte[] SIGNATURE_PREFIX = buildSignaturePrefix();

    private final PrivateKey priv;
    private final RSAPublicKey pub;

    private AdbCrypto(PrivateKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    // ---------------------------------------------------------------- key material

    /** Loads the stored key, generating one on first use. Slow; worker thread only. */
    static AdbCrypto load(Context c) throws Exception {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File fPriv = new File(dir, PRIV);
        File fPub = new File(dir, PUB);

        if (fPriv.exists() && fPub.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return new AdbCrypto(
                        kf.generatePrivate(new PKCS8EncodedKeySpec(read(fPriv))),
                        (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(read(fPub))));
            } catch (Exception bad) {
                // Corrupt pair: regenerate (costs one more approval dialog).
                fPriv.delete();
                fPub.delete();
            }
        }

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(KEY_BITS);
        KeyPair kp = g.generateKeyPair();
        write(fPriv, kp.getPrivate().getEncoded());
        write(fPub, kp.getPublic().getEncoded());
        return new AdbCrypto(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** True if a key already exists, i.e. the TV has most likely already authorised us. */
    static boolean hasKey(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        return new File(dir, PRIV).exists() && new File(dir, PUB).exists();
    }

    /** Deletes the local key. The TV keeps its copy until the user revokes authorisations. */
    static void forget(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        new File(dir, PRIV).delete();
        new File(dir, PUB).delete();
    }

    // ---------------------------------------------------------------- adb protocol bits

    /** Signs the adbd challenge. See the class comment for why this is hand-padded. */
    byte[] signToken(byte[] token) throws Exception {
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("adb token must be " + TOKEN_BYTES
                    + " bytes, got " + (token == null ? -1 : token.length));
        }
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        c.update(SIGNATURE_PREFIX);
        return c.doFinal(token);
    }

    /** The A_AUTH RSAPUBLICKEY payload: base64 struct, a space, a comment, and a NUL. */
    byte[] publicKeyPayload(Context c) {
        String body = Base64.encodeToString(encodePublicKey(), Base64.NO_WRAP);
        // Identifies this app's entry in adb_keys.
        String comment = " " + c.getPackageName() + "@" + safeModel();
        byte[] text = (body + comment).getBytes();
        byte[] out = new byte[text.length + 1];
        System.arraycopy(text, 0, out, 0, text.length);
        out[text.length] = 0;
        return out;
    }

    /**
     * The fingerprint shown in the TV's approval dialog, as AOSP
     * AdbDebuggingManager.getFingerprints() computes it: MD5 of the key blob, colon hex.
     */
    String fingerprint() {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(encodePublicKey());
            StringBuilder sb = new StringBuilder(d.length * 3);
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", d[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] encodePublicKey() {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        // n0inv = -1 / n[0] mod 2^32, i.e. 2^32 - ((n mod 2^32) inverted mod 2^32).
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        // Montgomery factor: r is 2^2048, so rr = r^2 mod n = 2^4096 mod n.
        BigInteger rr = BigInteger.ZERO.setBit(KEY_BITS * 2).mod(n);

        ByteBuffer b = ByteBuffer.allocate(PUBKEY_BLOB).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MODULUS_WORDS);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n));
        b.put(littleEndian(rr));
        b.putInt(pub.getPublicExponent().intValue());
        return b.array();
    }

    /** The low MODULUS_BYTES of v, least significant byte first. */
    private static byte[] littleEndian(BigInteger v) {
        byte[] be = v.toByteArray();        // big-endian, possibly with a leading sign byte
        byte[] out = new byte[MODULUS_BYTES];
        int n = Math.min(be.length, MODULUS_BYTES);
        for (int i = 0; i < n; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    private static byte[] buildSignaturePrefix() {
        int fillerLen = MODULUS_BYTES - 3 - SHA1_DIGEST_INFO.length - TOKEN_BYTES;
        byte[] p = new byte[MODULUS_BYTES - TOKEN_BYTES];
        int i = 0;
        p[i++] = 0x00;
        p[i++] = 0x01;
        for (int k = 0; k < fillerLen; k++) p[i++] = (byte) 0xFF;
        p[i++] = 0x00;
        System.arraycopy(SHA1_DIGEST_INFO, 0, p, i, SHA1_DIGEST_INFO.length);
        return p;
    }

    private static String safeModel() {
        String m = Build.MODEL;
        if (m == null || m.length() == 0) return "firetv";
        return m.replaceAll("[^A-Za-z0-9._-]", "");
    }

    // ---------------------------------------------------------------- tiny file helpers

    private static byte[] read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] out = new byte[(int) f.length()];
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) throw new IOException("short read on " + f);
                off += n;
            }
            return out;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
        } finally {
            try { out.close(); } catch (IOException ignored) { }
        }
    }
}
