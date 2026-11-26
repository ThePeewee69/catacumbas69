package com.catacumbas69.catasonarr.download;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A torrent's hash, from a magnet link ({@code xt=urn:btih:}) or a .torrent file (the SHA-1 of its bencoded
 * {@code info} dictionary), in upper case as Sonarr keeps download ids.
 */
public final class TorrentFiles {

    private TorrentFiles() {
    }

    /**
     * A magnet link's hash.
     *
     * @param magnet the link
     * @return the hash in upper-case hex, or {@code null}
     */
    public static String magnetHash(String magnet) {
        if (magnet == null || !magnet.startsWith("magnet:")) {
            return null;
        }
        String query = URI.create(magnet.replace(" ", "%20")).getRawSchemeSpecificPart();
        for (String part : query.replaceFirst("^\\?", "").split("&")) {
            String p = URLDecoder.decode(part, StandardCharsets.UTF_8);
            if (p.toLowerCase(Locale.ROOT).startsWith("xt=urn:btih:")) {
                String hash = p.substring(12);
                return hash.length() == 32 ? base32ToHex(hash) : hash.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * A .torrent file's hash.
     *
     * @param torrent the file's bytes
     * @return the hash in upper-case hex
     * @throws IllegalArgumentException when it is not a torrent
     */
    public static String torrentHash(byte[] torrent) {
        if (torrent.length == 0 || torrent[0] != 'd') {
            throw new IllegalArgumentException("Not a .torrent file");
        }
        int[] pos = {1};
        while (pos[0] < torrent.length && torrent[pos[0]] != 'e') {
            String key = new String(readString(torrent, pos), StandardCharsets.ISO_8859_1);
            int start = pos[0];
            skip(torrent, pos);
            if (key.equals("info")) {
                try {
                    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                    sha1.update(torrent, start, pos[0] - start);
                    return HexFormat.of().withUpperCase().formatHex(sha1.digest());
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalArgumentException("The .torrent file has no info dictionary");
    }

    private static byte[] readString(byte[] b, int[] pos) {
        int colon = pos[0];
        while (colon < b.length && b[colon] != ':') {
            colon++;
        }
        if (colon >= b.length) {
            throw new IllegalArgumentException("Bad .torrent file");
        }
        int length = Integer.parseInt(new String(b, pos[0], colon - pos[0], StandardCharsets.US_ASCII));
        byte[] out = new byte[length];
        System.arraycopy(b, colon + 1, out, 0, length);
        pos[0] = colon + 1 + length;
        return out;
    }

    private static void skip(byte[] b, int[] pos) {
        if (pos[0] >= b.length) {
            throw new IllegalArgumentException("Bad .torrent file");
        }
        byte c = b[pos[0]];
        if (c == 'i') {
            while (b[pos[0]] != 'e') {
                pos[0]++;
            }
            pos[0]++;
        } else if (c == 'l' || c == 'd') {
            pos[0]++;
            while (b[pos[0]] != 'e') {
                if (c == 'd') {
                    readString(b, pos);
                }
                skip(b, pos);
            }
            pos[0]++;
        } else if (c >= '0' && c <= '9') {
            readString(b, pos);
        } else {
            throw new IllegalArgumentException("Bad .torrent file");
        }
    }

    private static String base32ToHex(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder bits = new StringBuilder();
        for (char ch : base32.toUpperCase(Locale.ROOT).toCharArray()) {
            int v = alphabet.indexOf(ch);
            if (v < 0) {
                return base32.toUpperCase(Locale.ROOT);
            }
            bits.append(String.format("%5s", Integer.toBinaryString(v)).replace(' ', '0'));
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i + 4 <= bits.length(); i += 4) {
            hex.append(Integer.toHexString(Integer.parseInt(bits.substring(i, i + 4), 2)));
        }
        return hex.toString().toUpperCase(Locale.ROOT);
    }
}
