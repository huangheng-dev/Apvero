package io.apvero.platform.knowledge.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class KnowledgeCanonicalDigests {
    private static final String SHA256_PREFIX = "sha256:";

    private KnowledgeCanonicalDigests() {}

    static DigestBuilder builder(String domain) {
        return new DigestBuilder(domain);
    }

    static String text(String value) {
        Objects.requireNonNull(value, "APVERO_KNOWLEDGE_DIGEST_TEXT_REQUIRED");
        return bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    static String bytes(byte[] value) {
        Objects.requireNonNull(value, "APVERO_KNOWLEDGE_DIGEST_BYTES_REQUIRED");
        return SHA256_PREFIX + HexFormat.of().formatHex(sha256().digest(value));
    }

    static String vector(List<Float> vector) {
        Objects.requireNonNull(vector, "APVERO_KNOWLEDGE_VECTOR_REQUIRED");
        if (vector.isEmpty()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_VECTOR_INTEGRITY_INVALID");
        }
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(vector.size(), Float.BYTES))
                .order(ByteOrder.BIG_ENDIAN);
        double squaredNorm = 0;
        for (Float value : vector) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("APVERO_KNOWLEDGE_VECTOR_INTEGRITY_INVALID");
            }
            squaredNorm += (double) value * value;
            bytes.putInt(Float.floatToIntBits(value));
        }
        if (!(squaredNorm > 0) || !Double.isFinite(squaredNorm)) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_VECTOR_INTEGRITY_INVALID");
        }
        return bytes(bytes.array());
    }

    static UUID stableId(String identity) {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("APVERO_KNOWLEDGE_STABLE_IDENTITY_INVALID");
        }
        byte[] digest = sha256().digest(identity.getBytes(StandardCharsets.UTF_8));
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        long most = bytes.getLong();
        long least = bytes.getLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    static final class DigestBuilder {
        private final MessageDigest digest = sha256();

        private DigestBuilder(String domain) {
            addString(domain);
        }

        void addUuid(UUID value) {
            Objects.requireNonNull(value, "APVERO_KNOWLEDGE_DIGEST_UUID_REQUIRED");
            ByteBuffer bytes = ByteBuffer.allocate(2 * Long.BYTES);
            bytes.putLong(value.getMostSignificantBits());
            bytes.putLong(value.getLeastSignificantBits());
            add(bytes.array());
        }

        void addInt(int value) {
            add(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        }

        void addString(String value) {
            Objects.requireNonNull(value, "APVERO_KNOWLEDGE_DIGEST_STRING_REQUIRED");
            add(value.getBytes(StandardCharsets.UTF_8));
        }

        String finish() {
            return SHA256_PREFIX + HexFormat.of().formatHex(digest.digest());
        }

        private void add(byte[] value) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            digest.update(value);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("APVERO_SHA256_UNAVAILABLE", exception);
        }
    }
}
