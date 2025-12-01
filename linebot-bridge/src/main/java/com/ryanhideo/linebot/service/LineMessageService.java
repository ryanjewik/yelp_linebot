package com.ryanhideo.linebot.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class LineMessageService {
    
    public static class MessageResult {
        private final List<String> replies;
        private final List<List<String>> photos;
        private final String yelpConversationId;
        
        public MessageResult(List<String> replies, List<List<String>> photos, String yelpConversationId) {
            this.replies = replies;
            this.photos = photos;
            this.yelpConversationId = yelpConversationId;
        }
        
        public List<String> getReplies() {
            return replies;
        }
        
        public List<List<String>> getPhotos() {
            return photos;
        }
        
        public String getYelpConversationId() {
            return yelpConversationId;
        }
    }

    private final YelpService yelpService;
    private final MessageInsertService messageInsertService;

    public LineMessageService(YelpService yelpService, MessageInsertService messageInsertService) {
        this.yelpService = yelpService;
        this.messageInsertService = messageInsertService;
    }

    public MessageResult handleTextMessage(String rawText, String messageId, String lineConversationI, String userId, String msgType, String replyId) throws Exception {
        List<String> replies = new ArrayList<>();
        System.out.println("Received text message: " + rawText);
        String trimmed = rawText.trim();
        String lower = trimmed.toLowerCase();
        String yelpConversationId = null;
        boolean yelpCall = false;
        
        // /yelp command
        if (trimmed.startsWith("/yelp")) {
            yelpCall = true;
            String prompt = trimmed.substring("/yelp".length()).trim();
            if (prompt.isEmpty()) {
                replies.add("Usage: /yelp <your question>\nExample: /yelp Best ramen near me");
                List<List<String>> emptyPhotos = new ArrayList<>();
                emptyPhotos.add(new ArrayList<>());
                messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
                return new MessageResult(replies, emptyPhotos, yelpConversationId);
            } else {
                YelpService.YelpChatResult result = yelpService.callYelpChat(prompt, lineConversationI);
                replies.addAll(result.getMessages());
                yelpConversationId = result.getChatId();
                messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
                return new MessageResult(result.getMessages(), result.getPhotos(), yelpConversationId);
            }
        }
    
        // help / ping / echo
        List<List<String>> emptyPhotos = new ArrayList<>();
        emptyPhotos.add(new ArrayList<>());
        
        if (lower.equals("/help")) {
            replies.add(
                    "Commands:\n" +
                    "- /help: show this help\n" +
                    "- /ping: test latency\n" +
                    "- /echo <text>: I'll repeat your text\n" +
                    "- /yelp <query>: ask Yelp AI (e.g. /yelp good vegan sushi in SF)"
            );
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        } else if (lower.equals("/ping")) {
            replies.add("pong 🏓");
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        } else if (lower.startsWith("/echo ")) {
            replies.add(trimmed.substring(6).trim());
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        }

        // Insert message into database
        messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);

        return new MessageResult(replies, emptyPhotos, yelpConversationId);
    }
}
