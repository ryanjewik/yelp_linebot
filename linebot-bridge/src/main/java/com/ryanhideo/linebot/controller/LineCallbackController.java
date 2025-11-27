package com.ryanhideo.linebot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryanhideo.linebot.config.LineProperties;
import com.ryanhideo.linebot.service.LineMessageService;
import com.ryanhideo.linebot.util.FileLogger;
import com.ryanhideo.linebot.util.SignatureUtil;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@RestController
public class LineCallbackController {

    private static final String EVENTS_LOG_FILE = "events.log";
    private static final String LINE_REPLY_URL = "https://api.line.me/v2/bot/message/reply";

    private final LineProperties lineProps;
    private final LineMessageService messageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public LineCallbackController(LineProperties lineProps, LineMessageService messageService) {
        this.lineProps = lineProps;
        this.messageService = messageService;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
    }

    @PostMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestHeader("X-Line-Signature") String signature,
            @RequestBody String body
    ) {
        // verify signature
        boolean ok = SignatureUtil.isValidSignature(
                body,
                lineProps.getChannelSecret(),
                signature
        );

        if (!ok) {
            System.out.println("Invalid signature. Check channel secret/access token.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        // Log raw event JSON
        FileLogger.appendToFile(EVENTS_LOG_FILE, body);

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode events = root.path("events");

            if (events.isArray()) {
                for (JsonNode eventNode : events) {
                    handleSingleEvent(eventNode);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return ResponseEntity.ok("OK");
    }

    private void handleSingleEvent(JsonNode eventNode) {
        String type = eventNode.path("type").asText("");
        if (!"message".equals(type)) {
            return;
        }

        JsonNode messageNode = eventNode.path("message");
        String msgType = messageNode.path("type").asText("");
        if (!"text".equals(msgType)) {
            return;
        }

        String text = messageNode.path("text").asText("");
        String replyToken = eventNode.path("replyToken").asText("");

        if (replyToken.isEmpty()) {
            return;
        }

        List<String> replies = messageService.handleTextMessage(text);
        if (replies.isEmpty()) {
            return; // no reply for non-command messages (same as your Python else: return)
        }

        sendReply(replyToken, replies);
    }

    private void sendReply(String replyToken, List<String> messages) {
        try {
            // Build JSON body for LINE reply
            var root = objectMapper.createObjectNode();
            root.put("replyToken", replyToken);

            var msgArray = objectMapper.createArrayNode();
            int count = 0;
            for (String m : messages) {
                if (count >= 5) break;  // LINE limit
                var msgObj = objectMapper.createObjectNode();
                msgObj.put("type", "text");
                msgObj.put("text", m);
                msgArray.add(msgObj);
                count++;
            }
            root.set("messages", msgArray);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(lineProps.getChannelAccessToken());

            HttpEntity<String> entity =
                    new HttpEntity<>(objectMapper.writeValueAsString(root), headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(LINE_REPLY_URL, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                System.out.println("LINE reply error: " +
                        response.getStatusCodeValue() + " " +
                        response.getBody());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
