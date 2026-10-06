package com.veridoc.ai.document.api;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.veridoc.ai.document.api.dto.DocumentDtos.DocumentListItem;
import com.veridoc.ai.document.api.dto.DocumentDtos.DocumentStatusResponse;
import com.veridoc.ai.document.api.dto.DocumentDtos.UploadResponse;
import com.veridoc.ai.document.application.DocumentService;
import com.veridoc.ai.security.CurrentUser;
import com.veridoc.ai.security.authenticated.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@CurrentUser AuthenticatedUser user,
                                                 @RequestParam("file") MultipartFile file) throws IOException {
        UploadResponse response = documentService.upload(user, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<DocumentListItem>> list(@CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(documentService.list(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentStatusResponse> status(@CurrentUser AuthenticatedUser user,
                                                        @PathVariable("id") UUID id) {
        return ResponseEntity.ok(documentService.status(user, id));
    }
}