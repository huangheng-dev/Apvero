package io.apvero.platform.knowledge.api;

import io.apvero.platform.identity.RequestIdentityAttributes;
import io.apvero.platform.knowledge.AddInlineKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.AddUploadedKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.CreateInlineKnowledgeSourceCommand;
import io.apvero.platform.knowledge.CreateKnowledgeBaseCommand;
import io.apvero.platform.knowledge.CreateUploadedKnowledgeSourceCommand;
import io.apvero.platform.knowledge.KnowledgeBase;
import io.apvero.platform.knowledge.KnowledgeBaseCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.knowledge.KnowledgeSourceCatalog;
import io.apvero.platform.knowledge.KnowledgeSourceRevision;
import io.apvero.platform.knowledge.KnowledgeSourceSnapshot;
import io.apvero.platform.knowledge.SourceIngestionReceipt;
import io.apvero.platform.knowledge.SourceRevisionReceipt;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
final class KnowledgeController {
    private final KnowledgeBaseCatalog bases;
    private final KnowledgeSourceCatalog sources;

    KnowledgeController(KnowledgeBaseCatalog bases, KnowledgeSourceCatalog sources) {
        this.bases = bases;
        this.sources = sources;
    }

    @GetMapping("/knowledge-bases")
    List<KnowledgeBase> listBases(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return bases.list(workspaceId);
    }

    @PostMapping("/knowledge-bases")
    ResponseEntity<KnowledgeBase> createBase(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @RequestBody CreateBaseRequest request,
            HttpServletRequest httpRequest) {
        KnowledgeBase created = bases.create(workspaceId,
                new CreateKnowledgeBaseCommand(request.slug(), request.name(), request.description()),
                context(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/sources")
    List<KnowledgeSource> listSources(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID knowledgeBaseId) {
        return sources.listSources(workspaceId, knowledgeBaseId);
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/sources")
    ResponseEntity<SourceIngestionReceipt> createInlineSource(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID knowledgeBaseId,
            @RequestBody CreateInlineSourceRequest request,
            HttpServletRequest httpRequest) {
        if (request.sourceType() == KnowledgeSource.Type.WEB) {
            throw new KnowledgeException(
                    "APVERO_KNOWLEDGE_WEB_CAPTURE_NOT_AVAILABLE", Category.UNPROCESSABLE);
        }
        SourceIngestionReceipt receipt = sources.createInline(workspaceId, knowledgeBaseId,
                new CreateInlineKnowledgeSourceCommand(request.sourceType(), request.name(), request.content()),
                context(httpRequest));
        return ResponseEntity.accepted().body(receipt);
    }

    @PostMapping(path = "/knowledge-bases/{knowledgeBaseId}/source-uploads",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<SourceIngestionReceipt> uploadSource(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID knowledgeBaseId,
            @RequestPart("name") String name,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        try (InputStream content = file.getInputStream()) {
            SourceIngestionReceipt receipt = sources.createUpload(workspaceId, knowledgeBaseId,
                    new CreateUploadedKnowledgeSourceCommand(
                            name, file.getOriginalFilename(), file.getContentType(), file.getSize(), content),
                    context(httpRequest));
            return ResponseEntity.accepted().body(receipt);
        } catch (IOException exception) {
            throw new KnowledgeException("APVERO_KNOWLEDGE_CONTENT_READ_FAILED", Category.UNPROCESSABLE);
        }
    }

    @DeleteMapping("/knowledge-sources/{sourceId}")
    ResponseEntity<Void> tombstoneSource(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID sourceId,
            HttpServletRequest httpRequest) {
        sources.tombstone(workspaceId, sourceId, context(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/knowledge-sources/{sourceId}/revisions")
    List<KnowledgeSourceRevision> listRevisions(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID sourceId) {
        return sources.listRevisions(workspaceId, sourceId);
    }

    @PostMapping("/knowledge-sources/{sourceId}/revisions")
    ResponseEntity<SourceRevisionReceipt> addInlineRevision(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID sourceId,
            @RequestBody CreateInlineRevisionRequest request,
            HttpServletRequest httpRequest) {
        SourceRevisionReceipt receipt = sources.addInlineRevision(workspaceId, sourceId,
                new AddInlineKnowledgeSourceRevisionCommand(request.content()), context(httpRequest));
        return ResponseEntity.accepted().body(receipt);
    }

    @PostMapping(path = "/knowledge-sources/{sourceId}/revision-uploads",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<SourceRevisionReceipt> uploadRevision(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID sourceId,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        try (InputStream content = file.getInputStream()) {
            SourceRevisionReceipt receipt = sources.addUploadRevision(workspaceId, sourceId,
                    new AddUploadedKnowledgeSourceRevisionCommand(
                            file.getOriginalFilename(), file.getContentType(), file.getSize(), content),
                    context(httpRequest));
            return ResponseEntity.accepted().body(receipt);
        } catch (IOException exception) {
            throw new KnowledgeException("APVERO_KNOWLEDGE_CONTENT_READ_FAILED", Category.UNPROCESSABLE);
        }
    }

    @GetMapping("/knowledge-source-revisions/{revisionId}/content")
    ResponseEntity<byte[]> readRevisionContent(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID revisionId) {
        KnowledgeSourceSnapshot snapshot = sources.readRevisionContent(workspaceId, revisionId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(snapshot.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(snapshot.mediaType()))
                .contentLength(snapshot.bytes().length)
                .eTag(snapshot.contentDigest())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(snapshot.bytes());
    }

    private static KnowledgeCommandContext context(HttpServletRequest request) {
        Object actor = request.getAttribute(RequestIdentityAttributes.ACTOR);
        String traceId = request.getHeader("X-Request-Id");
        return new KnowledgeCommandContext(
                actor == null ? "anonymous" : actor.toString(),
                request.getRemoteAddr(),
                traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
    }

    record CreateBaseRequest(String slug, String name, String description) {}

    record CreateInlineSourceRequest(KnowledgeSource.Type sourceType, String name, String content) {}

    record CreateInlineRevisionRequest(String content) {}
}
