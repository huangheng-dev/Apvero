package io.apvero.platform.knowledge.internal;

import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.KnowledgeSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeSourceCapture {
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_MAGIC = {0x50, 0x4b, 0x03, 0x04};
    private static final byte[] WINDOWS_EXECUTABLE_MAGIC = {0x4d, 0x5a};
    private static final byte[] ELF_MAGIC = {0x7f, 0x45, 0x4c, 0x46};
    private static final byte[][] MACH_O_MAGICS = {
        {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce},
        {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xcf},
        {(byte) 0xce, (byte) 0xfa, (byte) 0xed, (byte) 0xfe},
        {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe},
        {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe}
    };

    private final KnowledgeProperties properties;

    KnowledgeSourceCapture(KnowledgeProperties properties) {
        this.properties = properties;
    }

    KnowledgeCapturedSnapshot inline(KnowledgeSource.Type sourceType, String content) {
        if (sourceType != KnowledgeSource.Type.TEXT && sourceType != KnowledgeSource.Type.MARKDOWN) {
            throw problem("APVERO_KNOWLEDGE_INLINE_TYPE_INVALID", Category.BAD_REQUEST);
        }
        if (content == null || content.isEmpty()) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_REQUIRED", Category.BAD_REQUEST);
        }
        int characters = content.codePointCount(0, content.length());
        if (characters > properties.maxInlineCharacters()) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE", Category.CONTENT_TOO_LARGE);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        requireSnapshotSize(bytes.length);
        String mediaType = sourceType == KnowledgeSource.Type.MARKDOWN
                ? "text/markdown; charset=utf-8"
                : "text/plain; charset=utf-8";
        return snapshot(sourceType, mediaType, null, bytes);
    }

    KnowledgeCapturedSnapshot upload(
            String originalFilename,
            String declaredMediaType,
            long declaredSize,
            InputStream input) {
        if (input == null) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_REQUIRED", Category.BAD_REQUEST);
        }
        if (declaredSize > properties.maxSnapshotBytes()) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE", Category.CONTENT_TOO_LARGE);
        }
        byte[] bytes = readBounded(input);
        if (bytes.length == 0) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_REQUIRED", Category.BAD_REQUEST);
        }
        String filename = safeFilename(originalFilename);
        if (isExecutable(bytes)) {
            throw problem("APVERO_KNOWLEDGE_EXECUTABLE_REJECTED", Category.UNSUPPORTED_MEDIA);
        }
        if (startsWith(bytes, ZIP_MAGIC)) {
            inspectDocx(bytes);
            return snapshot(KnowledgeSource.Type.DOCX,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", filename, bytes);
        }
        if (indexOf(bytes, PDF_MAGIC, 1024) >= 0) {
            inspectPdf(bytes);
            return snapshot(KnowledgeSource.Type.PDF, "application/pdf", filename, bytes);
        }
        requireUtf8(bytes);
        KnowledgeSource.Type textType = isMarkdownHint(filename, declaredMediaType)
                ? KnowledgeSource.Type.MARKDOWN
                : KnowledgeSource.Type.TEXT;
        String mediaType = textType == KnowledgeSource.Type.MARKDOWN
                ? "text/markdown; charset=utf-8"
                : "text/plain; charset=utf-8";
        return snapshot(textType, mediaType, filename, bytes);
    }

    private byte[] readBounded(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(properties.maxSnapshotBytes() + 1);
            requireSnapshotSize(bytes.length);
            return bytes;
        } catch (IOException exception) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_READ_FAILED", Category.UNPROCESSABLE);
        }
    }

    private void requireSnapshotSize(int byteSize) {
        if (byteSize > properties.maxSnapshotBytes()) {
            throw problem("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE", Category.CONTENT_TOO_LARGE);
        }
    }

    private void inspectPdf(byte[] bytes) {
        int tailStart = Math.max(0, bytes.length - 2048);
        String trailer = new String(bytes, tailStart, bytes.length - tailStart, StandardCharsets.ISO_8859_1);
        String document = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!trailer.contains("%%EOF")) {
            throw problem("APVERO_KNOWLEDGE_PDF_MALFORMED", Category.UNPROCESSABLE);
        }
        if (document.contains("/Encrypt")) {
            throw problem("APVERO_KNOWLEDGE_PDF_ENCRYPTED", Category.UNPROCESSABLE);
        }
    }

    private void inspectDocx(byte[] bytes) {
        int entries = 0;
        long expandedBytes = 0;
        boolean contentTypes = false;
        boolean documentXml = false;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > properties.maxDocxEntries()) {
                    throw problem("APVERO_KNOWLEDGE_ARCHIVE_ENTRY_LIMIT", Category.UNPROCESSABLE);
                }
                String name = entry.getName();
                if (name == null || name.isBlank() || !names.add(name) || unsafeArchivePath(name)) {
                    throw problem("APVERO_KNOWLEDGE_DOCX_MALFORMED", Category.UNPROCESSABLE);
                }
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (lowerName.endsWith("vbaproject.bin") || lowerName.contains("/embeddings/")) {
                    throw problem("APVERO_KNOWLEDGE_DOCX_ACTIVE_CONTENT", Category.UNSUPPORTED_MEDIA);
                }
                contentTypes |= "[content_types].xml".equals(lowerName);
                documentXml |= "word/document.xml".equals(lowerName);
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > properties.maxDocxExpandedBytes()) {
                        throw problem("APVERO_KNOWLEDGE_ARCHIVE_EXPANSION_LIMIT", Category.UNPROCESSABLE);
                    }
                }
                zip.closeEntry();
            }
        } catch (KnowledgeException exception) {
            throw exception;
        } catch (ZipException exception) {
            String code = exception.getMessage() != null
                            && exception.getMessage().toLowerCase(Locale.ROOT).contains("encrypt")
                    ? "APVERO_KNOWLEDGE_DOCX_ENCRYPTED"
                    : "APVERO_KNOWLEDGE_DOCX_MALFORMED";
            throw problem(code, Category.UNPROCESSABLE);
        } catch (IOException exception) {
            throw problem("APVERO_KNOWLEDGE_DOCX_MALFORMED", Category.UNPROCESSABLE);
        }
        if (!contentTypes || !documentXml) {
            throw problem("APVERO_KNOWLEDGE_DOCX_MALFORMED", Category.UNPROCESSABLE);
        }
    }

    private void requireUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw problem("APVERO_KNOWLEDGE_MEDIA_UNSUPPORTED", Category.UNSUPPORTED_MEDIA);
        }
    }

    private KnowledgeCapturedSnapshot snapshot(
            KnowledgeSource.Type sourceType,
            String mediaType,
            String originalFilename,
            byte[] bytes) {
        return new KnowledgeCapturedSnapshot(sourceType, mediaType, originalFilename, digest(bytes), bytes);
    }

    private static boolean isExecutable(byte[] bytes) {
        if (startsWith(bytes, WINDOWS_EXECUTABLE_MAGIC) || startsWith(bytes, ELF_MAGIC)) {
            return true;
        }
        for (byte[] magic : MACH_O_MAGICS) {
            if (startsWith(bytes, magic)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarkdownHint(String filename, String declaredMediaType) {
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String lowerType = declaredMediaType == null ? "" : declaredMediaType.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
                || lowerType.startsWith("text/markdown");
    }

    private static boolean unsafeArchivePath(String name) {
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("/") || normalized.contains("../") || normalized.contains("\u0000");
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
        StringBuilder safe = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint) && codePoint != '/' && codePoint != '\\') {
                safe.appendCodePoint(codePoint);
            }
        });
        String result = safe.toString().trim();
        if (result.isEmpty()) {
            return null;
        }
        if (result.codePointCount(0, result.length()) <= 512) {
            return result;
        }
        return result.substring(0, result.offsetByCodePoints(0, 512));
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] bytes, byte[] target, int maximumStart) {
        int limit = Math.min(bytes.length - target.length, maximumStart);
        for (int index = 0; index <= limit; index++) {
            boolean match = true;
            for (int offset = 0; offset < target.length; offset++) {
                if (bytes[index + offset] != target[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }
        return -1;
    }

    private static KnowledgeException problem(String code, Category category) {
        return new KnowledgeException(code, category);
    }
}
