package com.ryanhideo.linebot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryanhideo.linebot.config.LineProperties;
import com.ryanhideo.linebot.service.LineMessageService;
import com.ryanhideo.linebot.service.MessageInsertService;
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
    private static final String LINE_PUSH_URL = "https://api.line.me/v2/bot/message/push";

    private final LineProperties lineProps;
    private final LineMessageService messageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final MessageInsertService messageInsertService;

    public LineCallbackController(LineProperties lineProps, LineMessageService messageService, MessageInsertService messageInsertService) {
        this.lineProps = lineProps;
        this.messageService = messageService;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
        this.messageInsertService = messageInsertService;
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
            System.out.println("Received events: " + events.toString());
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
        String messageId = messageNode.path("id").asText("");
        String chattype = eventNode.path("source").path("type").asText(""); // used to indicate if it's a dm or a gc
        String userId = eventNode.path("source").path("userId").asText("");
        
        String lineConversationId;
        if (chattype.equals("group") || chattype.equals("room")) {
            lineConversationId = eventNode.path("source").path("groupId").asText("");
            if (lineConversationId.isEmpty()) {
                lineConversationId = eventNode.path("source").path("roomId").asText("");
            }
        } else {
            lineConversationId = eventNode.path("source").path("userId").asText("");
        }
        
        String replyId = messageNode.path("quotedMessageId").asText("");

        if (msgType.equals("image")) {
            System.out.println("image received");
            return;
        }
        
        if (!msgType.equals("text")) {
            return;
        }

        String text = messageNode.path("text").asText("");
        String replyToken = eventNode.path("replyToken").asText("");

        if (replyToken.isEmpty()) {
            return;
        }

        try {
            // Check if this is a /yelp command
            if (text.trim().startsWith("/yelp")) {
                // Send immediate acknowledgment
                sendReply(replyToken, List.of("🔍 Processing your Yelp request..."), messageId, lineConversationId, userId, msgType, replyId, null);
                
                // Process the actual request
                LineMessageService.MessageResult result = messageService.handleTextMessage(text, messageId, lineConversationId, userId, msgType, replyId);
                
                // Send the actual results via push message
                if (!result.getReplies().isEmpty()) {
                    sendPushMessage(lineConversationId, result.getReplies(), result.getPhotos(), messageId, lineConversationId, userId, msgType, replyId, result.getYelpConversationId());
                }
            } else {
                // Non-yelp commands use normal reply flow
                LineMessageService.MessageResult result = messageService.handleTextMessage(text, messageId, lineConversationId, userId, msgType, replyId);
                if (result.getReplies().isEmpty()) {
                    return; // no reply for non-command messages
                }
                sendReply(replyToken, result.getReplies(), messageId, lineConversationId, userId, msgType, replyId, result.getYelpConversationId());
            }
        } catch (Exception e) {
            System.err.println("Error handling message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendReply(String replyToken, List<String> messages, String messageId, String lineConversationId, String userId, String msgType, String replyId, String yelpConversationId) {
        try {
            // Build JSON body for LINE reply
            var root = objectMapper.createObjectNode();
            root.put("replyToken", replyToken);

            var msgArray = objectMapper.createArrayNode();
            int count = 0;
            for (String m : messages) { // here we will update postgres and clean up the message formats
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
            } else {
                System.out.println("Replied to LINE message successfully.");
            }
            var responseJson = objectMapper.readTree(response.getBody()).path("sentMessages");
            int index = 0;
            for (JsonNode item: responseJson) {
                System.out.println("Reply item: " + item.toString());
                String replyMessageId = item.path("id").asText("");
                // Insert each reply message into the database
                messageInsertService.insertMessage(msgArray.get(index).path("text").asText(""), false, replyMessageId, lineConversationId, "-1", msgType, replyId, yelpConversationId);
                index++;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendPushMessage(String targetId, List<String> messages, List<List<String>> photosList, String messageId, String lineConversationId, String userId, String msgType, String replyId, String yelpConversationId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(lineProps.getChannelAccessToken());

            // Send each message with its photos as a separate push API call
            for (int i = 0; i < messages.size(); i++) {
                String message = messages.get(i);
                List<String> photos = (i < photosList.size()) ? photosList.get(i) : new ArrayList<>();
                
                // Truncate message if too long (LINE limit is 5000 chars)
                String truncatedMsg = message;
                if (message.length() > 4900) {
                    truncatedMsg = message.substring(0, 4900) + "\n\n...(response truncated due to length)";
                }

                // Build JSON body for this single message with photos
                var root = objectMapper.createObjectNode();
                root.put("to", targetId);

                var msgArray = objectMapper.createArrayNode();
                
                // Add text message
                var msgObj = objectMapper.createObjectNode();
                msgObj.put("type", "text");
                msgObj.put("text", truncatedMsg);
                msgArray.add(msgObj);
                
                // Add image messages (LINE supports up to 5 messages per push)
                int photoCount = 0;
                for (String photoUrl : photos) {
                    if (photoCount >= 4) break; // Leave room for the text message (max 5 total)
                    var imgObj = objectMapper.createObjectNode();
                    imgObj.put("type", "image");
                    imgObj.put("originalContentUrl", photoUrl);
                    imgObj.put("previewImageUrl", photoUrl);
                    msgArray.add(imgObj);
                    photoCount++;
                }
                
                root.set("messages", msgArray);

                HttpEntity<String> entity =
                        new HttpEntity<>(objectMapper.writeValueAsString(root), headers);

                ResponseEntity<String> response =
                        restTemplate.exchange(LINE_PUSH_URL, HttpMethod.POST, entity, String.class);

                if (!response.getStatusCode().is2xxSuccessful()) {
                    System.err.println("LINE push error: " +
                            response.getStatusCodeValue() + " " +
                            response.getBody());
                } else {
                    System.out.println("Pushed LINE message successfully.");
                }

                // Insert the message into the database
                messageInsertService.insertMessage(truncatedMsg, false, "push-" + System.currentTimeMillis(), lineConversationId, "-1", msgType, replyId, yelpConversationId);
                
                // Small delay between messages to ensure proper ordering
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

        } catch (org.springframework.web.client.ResourceAccessException e) {
            System.err.println("LINE push API connection error (network/timeout): " + e.getMessage());
            System.err.println("Messages were processed but could not be delivered to user. They may need to retry.");
            // Don't rethrow - allow the webhook to complete successfully even if push fails
        } catch (Exception e) {
            System.err.println("LINE push API error: " + e.getMessage());
            e.printStackTrace();
            // Don't rethrow - allow the webhook to complete successfully even if push fails
        }
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
