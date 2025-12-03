package com.ryanhideo.linebot.controller;

import com.ryanhideo.linebot.service.DemoChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@CrossOrigin(origins = "*")
public class DemoChatController {

    private final DemoChatService demoChatService;

    public DemoChatController(DemoChatService demoChatService) {
        this.demoChatService = demoChatService;
    }

    @PostMapping("/chat")
    public ResponseEntity<DemoChatService.DemoChatResponse> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String chatId = request.get("chatId");
        
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        DemoChatService.DemoChatResponse response = demoChatService.processMessage(message, chatId);
        return ResponseEntity.ok(response);
    }
}
