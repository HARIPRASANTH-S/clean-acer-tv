package com.gathin.tvhome;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Just enough of the ADB wire protocol to run shell commands against the TV's own adbd on
 * 127.0.0.1:5555, the port USB debugging opens on many TVs. This is how {@link Keeper} gets the
 * shell-level rights (pm disable-user, appops, set-home-activity) an ordinary app does not have.
 *
 * The app keeps its own RSA key in its private files. The first connection shows the TV's
 * "Allow USB debugging?" prompt for that key; after "Always allow" it connects silently.
 */
final class AdbLocal implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** DER prefix of a SHA-1 DigestInfo: adbd checks the token signature as if the token were a SHA-1 hash. */
    private static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
            0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private AdbLocal(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Connects and authenticates; waits up to authWaitMs for someone to accept the prompt on screen. */
    static AdbLocal connect(Context ctx, int port, int authWaitMs) throws IOException, GeneralSecurityException {
        KeyPair key = loadOrCreateKey(ctx);
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        s.setSoTimeout(15000);
        AdbLocal adb = new AdbLocal(s);
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false, offeredKey = false;
            while (true) {
                Msg m = adb.read();
                if (m.cmd == CNXN) return adb;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb reply " + Integer.toHexString(m.cmd));
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(key.getPrivate(), m.data));
                    signed = true;
                } else if (!offeredKey) {
                    // Key not trusted yet: offer it, which puts the "Allow USB debugging?" prompt on screen.
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob((RSAPublicKey) key.getPublic()));
                    offeredKey = true;
                    s.setSoTimeout(authWaitMs);
                } else {
                    throw new IOException("adb key refused");
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    /** Runs one command through the shell service and returns what it printed. */
    String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue; // late packets from an earlier stream
            if (m.cmd == WRTE) {
                text.write(m.data);
                send(OKAY, local, m.arg0, null);
            } else if (m.cmd == CLSE) {
                if (m.arg0 != 0) send(CLSE, local, m.arg0, null);
                return text.toString("UTF-8");
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > MAX_DATA) throw new IOException("bad adb packet length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(key);
        sig.update(SHA1_DIGEST_INFO);
        sig.update(token);
        return sig.sign();
    }

    /** adbd's key format: Android's mincrypt RSAPublicKey struct, base64, then a name. */
    private static byte[] publicKeyBlob(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue()).put(littleEndian(n, 256)).put(littleEndian(rr, 256))
                .putInt(pub.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhome@" + Build.MODEL + "\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    private static KeyPair loadOrCreateKey(Context ctx) throws IOException, GeneralSecurityException {
        File priv = new File(ctx.getNoBackupFilesDir(), "adbkey");
        File pub = new File(ctx.getNoBackupFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        Files.write(priv.toPath(), kp.getPrivate().getEncoded());
        Files.write(pub.toPath(), kp.getPublic().getEncoded());
        return kp;
    }
}
