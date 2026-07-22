package io.apvero.platform.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class KnowledgeSourceCaptureTest {
    private final KnowledgeSourceCapture capture = new KnowledgeSourceCapture(
            new KnowledgeProperties(true, URI.create("http://ai-worker:8090"), 8, 1_024, 8, 4_096));

    @Test
    void capturesExactUtf8BytesAndUsesCodePointLimits() {
        KnowledgeCapturedSnapshot snapshot = capture.inline(KnowledgeSource.Type.MARKDOWN, "# 你好");

        assertThat(snapshot.sourceType()).isEqualTo(KnowledgeSource.Type.MARKDOWN);
        assertThat(snapshot.mediaType()).isEqualTo("text/markdown; charset=utf-8");
        assertThat(snapshot.bytes()).containsExactly("# 你好".getBytes(StandardCharsets.UTF_8));
        assertThat(snapshot.contentDigest()).matches("^sha256:[a-f0-9]{64}$");

        assertThatThrownBy(() -> capture.inline(KnowledgeSource.Type.TEXT, "123456789"))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_CONTENT_TOO_LARGE");
    }

    @Test
    void detectsActualPdfAndRejectsMalformedOrEncryptedPdf() {
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        KnowledgeCapturedSnapshot snapshot = capture.upload(
                "spoofed.txt", "text/plain", pdf.length, new ByteArrayInputStream(pdf));

        assertThat(snapshot.sourceType()).isEqualTo(KnowledgeSource.Type.PDF);
        assertThat(snapshot.mediaType()).isEqualTo("application/pdf");

        byte[] malformed = "%PDF-1.4\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1);
        assertCode(malformed, "APVERO_KNOWLEDGE_PDF_MALFORMED");

        byte[] encrypted = "%PDF-1.4\n/Encrypt true\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        assertCode(encrypted, "APVERO_KNOWLEDGE_PDF_ENCRYPTED");
    }

    @Test
    void acceptsOnlyDocxStructureAndRejectsActiveContent() throws Exception {
        KnowledgeCapturedSnapshot snapshot = capture.upload(
                "document.zip", "application/zip", -1, new ByteArrayInputStream(docx(false)));
        assertThat(snapshot.sourceType()).isEqualTo(KnowledgeSource.Type.DOCX);

        assertThatThrownBy(() -> capture.upload(
                        "macro.docm", "application/zip", -1, new ByteArrayInputStream(docx(true))))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_DOCX_ACTIVE_CONTENT");

        byte[] ordinaryZip = zip(new Entry("data.txt", "not a docx".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> capture.upload(
                        "ordinary.zip", "application/zip", -1, new ByteArrayInputStream(ordinaryZip)))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo("APVERO_KNOWLEDGE_DOCX_MALFORMED");
    }

    @Test
    void rejectsExecutablesInvalidUtf8AndBodiesBeyondTheBound() {
        assertCode(new byte[] {'M', 'Z', 0, 1}, "APVERO_KNOWLEDGE_EXECUTABLE_REJECTED");
        assertCode(new byte[] {(byte) 0xc3, 0x28}, "APVERO_KNOWLEDGE_MEDIA_UNSUPPORTED");
        assertCode(new byte[1_025], "APVERO_KNOWLEDGE_CONTENT_TOO_LARGE");
    }

    private void assertCode(byte[] bytes, String code) {
        assertThatThrownBy(() -> capture.upload(
                        "source.bin", "application/octet-stream", bytes.length, new ByteArrayInputStream(bytes)))
                .isInstanceOf(KnowledgeException.class)
                .extracting(exception -> ((KnowledgeException) exception).code())
                .isEqualTo(code);
    }

    private static byte[] docx(boolean macro) throws Exception {
        if (macro) {
            return zip(
                    new Entry("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8)),
                    new Entry("word/document.xml", "<document/>".getBytes(StandardCharsets.UTF_8)),
                    new Entry("word/vbaProject.bin", new byte[] {1, 2, 3}));
        }
        return zip(
                new Entry("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8)),
                new Entry("word/document.xml", "<document/>".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] zip(Entry... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.bytes());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record Entry(String name, byte[] bytes) {}
}
