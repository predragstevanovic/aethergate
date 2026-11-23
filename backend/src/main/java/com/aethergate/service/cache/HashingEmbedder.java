package com.aethergate.service.cache;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, offline 384-dimensional text embedding (feature hashing).
 *
 * <p>Features: word unigrams (weight 1.0), word bigrams (0.7) and character trigrams of each
 * word (0.35), hashed with 64-bit FNV-1a into 384 signed buckets, sub-linear TF, L2-normalised.
 * Identical or trivially re-phrased prompts (case, punctuation, whitespace, diacritics) map to
 * the same vector; different questions land far apart. It is not a neural embedding, which is
 * exactly the point: the semantic cache works with zero external calls and zero cost.
 */
public final class HashingEmbedder {

    public static final int DIMENSIONS = 384;

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private HashingEmbedder() {}

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        String stripped = DIACRITICS.matcher(decomposed).replaceAll("");
        return NON_WORD.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    public static float[] embed(String text) {
        double[] acc = new double[DIMENSIONS];
        String normalized = normalize(text);
        if (!normalized.isEmpty()) {
            String[] words = normalized.split(" ");
            for (int i = 0; i < words.length; i++) {
                add(acc, "w:" + words[i], 1.0);
                if (i + 1 < words.length) {
                    add(acc, "b:" + words[i] + ' ' + words[i + 1], 0.7);
                }
                String padded = '^' + words[i] + '$';
                for (int j = 0; j + 3 <= padded.length(); j++) {
                    add(acc, "c:" + padded.substring(j, j + 3), 0.35);
                }
            }
        }
        double norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            // sub-linear term frequency keeps one repeated word from dominating
            acc[i] = Math.signum(acc[i]) * Math.log1p(Math.abs(acc[i]));
            norm += acc[i] * acc[i];
        }
        float[] out = new float[DIMENSIONS];
        if (norm == 0) {
            return out;
        }
        double inv = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            out[i] = (float) (acc[i] * inv);
        }
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /** pgvector text literal, e.g. {@code [0.1,-0.2,...]}. */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private static void add(double[] acc, String feature, double weight) {
        long h = fnv1a(feature);
        int bucket = (int) Long.remainderUnsigned(h, DIMENSIONS);
        double sign = ((h >>> 63) == 0) ? 1.0 : -1.0;
        acc[bucket] += sign * weight;
    }

    static long fnv1a(String s) {
        long hash = FNV_OFFSET;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
