package com.chatbot.agent.controller;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload/{chatbotId}")
    public ResponseEntity<Model.Document> uploadDocument(
            @PathVariable Long chatbotId,
            @RequestParam("file") MultipartFile file) throws IOException {

        return ResponseEntity.ok(documentService.uploadDocument(chatbotId, file));
    }
}