package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ryanhideo.linebot.config.YelpProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Service
public class DemoChatService {

    private static final String YELP_AI_URL = "https://api.yelp.com/ai/chat/v2";

    private final YelpProperties yelpProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DemoChatService(YelpProperties yelpProperties) {
        this.yelpProperties = yelpProperties;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public static class DemoChatResponse {
        private final String message;
        private final String chatId;

        public DemoChatResponse(String message, String chatId) {
            this.message = message;
            this.chatId = chatId;
        }

        public String getMessage() {
            return message;
        }

        public String getChatId() {
            return chatId;
        }
    }

    public DemoChatResponse processMessage(String userMessage, String chatId) {
        try {
            System.out.println("DemoChat - Received chatId: " + chatId);
            
            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("query", userMessage);
            
            // Add chat_id if we have one (for follow-up messages to maintain conversation context)
            if (chatId != null && !chatId.isEmpty()) {
                requestBody.put("chat_id", chatId);
                System.out.println("DemoChat - Adding chat_id to request: " + chatId);
            }

            System.out.println("DemoChat - Request: " + requestBody.toString());

            // Set up headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + yelpProperties.getApiKey());

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);

            // Call Yelp AI API
            ResponseEntity<String> response = restTemplate.exchange(
                YELP_AI_URL,
                HttpMethod.POST,
                entity,
                String.class
            );

            // Parse response - structure is: {"response": {"text": "..."}, "chat_id": "..."}
            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String botMessage = responseJson.path("response").path("text").asText("");
            String newChatId = responseJson.path("chat_id").asText("");
            
            System.out.println("DemoChat - Response chat_id: " + newChatId);
            System.out.println("DemoChat - Response message length: " + (botMessage != null ? botMessage.length() : 0));
            
            // Use the chat_id from response, or keep the existing one if not provided
            // The API should return the same chat_id for maintaining conversation context
            if (newChatId == null || newChatId.isEmpty()) {
                // If API didn't return chat_id, use the one we sent or generate new
                newChatId = (chatId != null && !chatId.isEmpty()) ? chatId : UUID.randomUUID().toString();
                System.out.println("DemoChat - Using/Generated chat ID: " + newChatId);
            }

            // If no message, use a default
            if (botMessage == null || botMessage.isEmpty()) {
                botMessage = "Sorry, I couldn't generate a response.";
            }

            return new DemoChatResponse(botMessage, newChatId);

        } catch (Exception e) {
            e.printStackTrace();
            String errorChatId = (chatId != null && !chatId.isEmpty()) ? chatId : UUID.randomUUID().toString();
            return new DemoChatResponse("Sorry, I encountered an error processing your request.", errorChatId);
        }
    }
}
