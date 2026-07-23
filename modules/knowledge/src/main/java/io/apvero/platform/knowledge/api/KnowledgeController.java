package io.apvero.platform.knowledge.api;

import io.apvero.platform.identity.RequestIdentityAttributes;
import io.apvero.platform.knowledge.AddInlineKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.AddUploadedKnowledgeSourceRevisionCommand;
import io.apvero.platform.knowledge.CreateInlineKnowledgeSourceCommand;
import io.apvero.platform.knowledge.CreateKnowledgeBaseCommand;
import io.apvero.platform.knowledge.CreateUploadedKnowledgeSourceCommand;
import io.apvero.platform.knowledge.CreateWebKnowledgeSourceCommand;
import io.apvero.platform.knowledge.KnowledgeBase;
import io.apvero.platform.knowledge.KnowledgeBaseCatalog;
import io.apvero.platform.knowledge.KnowledgeCommandContext;
import io.apvero.platform.knowledge.KnowledgeException;
import io.apvero.platform.knowledge.KnowledgeException.Category;
import io.apvero.platform.knowledge.KnowledgeIngestionJob;
import io.apvero.platform.knowledge.KnowledgeIngestionJobCatalog;
import io.apvero.platform.knowledge.KnowledgeSource;
import io.apvero.platform.knowledge.KnowledgeSourceCatalog;
import io.apvero.platform.knowledge.KnowledgeSourceRevision;
import io.apvero.platform.knowledge.KnowledgeSourceSnapshot;
import io.apvero.platform.knowledge.SourceIngestionReceipt;
import io.apvero.platform.knowledge.SourceRevisionReceipt;
import io.apvero.platform.knowledge.SourceSyncReceipt;
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
    private final KnowledgeIngestionJobCatalog jobs;

    KnowledgeController(
            KnowledgeBaseCatalog bases,
            KnowledgeSourceCatalog sources,
            KnowledgeIngestionJobCatalog jobs) {
        this.bases = bases;
        this.sources = sources;
        this.jobs = jobs;
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
        SourceIngestionReceipt receipt;
        if (request.sourceType() == KnowledgeSource.Type.WEB) {
            if (request.content() != null) {
                throw new KnowledgeException("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
            }
            receipt = sources.createWeb(workspaceId, knowledgeBaseId,
                    new CreateWebKnowledgeSourceCommand(request.name(), parseUri(request.url())),
                    context(httpRequest));
        } else {
            if (request.url() != null) {
                throw new KnowledgeException("APVERO_KNOWLEDGE_REQUEST_INVALID", Category.BAD_REQUEST);
            }
            receipt = sources.createInline(workspaceId, knowledgeBaseId,
                    new CreateInlineKnowledgeSourceCommand(request.sourceType(), request.name(), request.content()),
                    context(httpRequest));
        }
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

    @PostMapping("/knowledge-sources/{sourceId}/sync")
    ResponseEntity<SourceSyncReceipt> synchronizeWebSource(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID sourceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted().body(sources.synchronizeWeb(workspaceId, sourceId, context(httpRequest)));
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

    @GetMapping("/knowledge-ingestion-jobs")
    List<KnowledgeIngestionJob> listJobs(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID knowledgeBaseId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
        return jobs.list(workspaceId, knowledgeBaseId, parseJobStatus(status));
    }

    @GetMapping("/knowledge-ingestion-jobs/{jobId}")
    KnowledgeIngestionJob getJob(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID jobId) {
        return jobs.get(workspaceId, jobId);
    }

    @PostMapping("/knowledge-ingestion-jobs/{jobId}/retry")
    ResponseEntity<KnowledgeIngestionJob> retryJob(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID jobId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted().body(jobs.retry(workspaceId, jobId, context(httpRequest)));
    }

    @PostMapping("/knowledge-ingestion-jobs/{jobId}/cancel")
    KnowledgeIngestionJob cancelJob(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @PathVariable UUID jobId,
            HttpServletRequest httpRequest) {
        return jobs.cancel(workspaceId, jobId, context(httpRequest));
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

    private static java.net.URI parseUri(String value) {
        try {
            return value == null ? null : java.net.URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeException("APVERO_KNOWLEDGE_WEB_URI_INVALID", Category.BAD_REQUEST);
        }
    }

    private static KnowledgeIngestionJob.Status parseJobStatus(String value) {
        try {
            return value == null ? null : KnowledgeIngestionJob.Status.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeException("APVERO_KNOWLEDGE_JOB_STATUS_INVALID", Category.BAD_REQUEST);
        }
    }

    record CreateInlineSourceRequest(KnowledgeSource.Type sourceType, String name, String content, String url) {}

    record CreateInlineRevisionRequest(String content) {}
}
