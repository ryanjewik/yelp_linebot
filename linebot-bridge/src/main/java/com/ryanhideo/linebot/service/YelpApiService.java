package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ryanhideo.linebot.config.YelpProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Service for making direct calls to Yelp Fusion AI API.
 * Replaces the Python Flask MCP server with native Java implementation.
 */
@Service
public class YelpApiService {
    
    private final YelpProperties yelpProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final YelpResponseFormatter formatter;
    
    private static final String YELP_AI_CHAT_URL = "https://api.yelp.com/ai/chat/v2";
    
    public YelpApiService(YelpProperties yelpProperties) {
        this.yelpProperties = yelpProperties;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
        this.formatter = new YelpResponseFormatter();
    }
    
    /**
     * Make a request to Yelp Fusion AI Chat API.
     * 
     * @param query Natural language query for restaurants
     * @param chatId Optional chat ID for conversation continuity
     * @param latitude Optional user latitude for location context
     * @param longitude Optional user longitude for location context
     * @return YelpChatResult containing formatted response and chat ID
     */
    public YelpChatResult queryYelpAI(String query, String chatId, Double latitude, Double longitude) {
        try {
            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("query", query);
            requestBody.put("with_reasoning", true); // Request AI reasoning for recommendations
            
            if (chatId != null && !chatId.isEmpty()) {
                requestBody.put("chat_id", chatId);
            }
            
            if (latitude != null && longitude != null) {
                ObjectNode userContext = objectMapper.createObjectNode();
                userContext.put("latitude", latitude);
                userContext.put("longitude", longitude);
                requestBody.set("user_context", userContext);
            }
            
            // Set up headers with Yelp API key
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(yelpProperties.getApiKey());
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            
            System.out.println("[YELP_API] Query: " + query);
            System.out.println("[YELP_API] Chat ID: " + chatId);
            System.out.println("[YELP_API] Lat/Lon: " + latitude + "/" + longitude);
            
            // Make API call
            ResponseEntity<String> response = restTemplate.exchange(
                YELP_AI_CHAT_URL,
                HttpMethod.POST,
                entity,
                String.class
            );
            
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("[YELP_API] Error: HTTP " + response.getStatusCodeValue());
                return new YelpChatResult("Unable to fetch data from Yelp.", chatId, null);
            }
            
            // Parse response
            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String returnedChatId = responseJson.path("chat_id").asText(chatId);
            
            System.out.println("[YELP_API] Returned Chat ID: " + returnedChatId);
            System.out.println("[YELP_API] Response has 'entities': " + responseJson.has("entities"));
            if (responseJson.has("entities") && responseJson.path("entities").isArray()) {
                System.out.println("[YELP_API] Entities array size: " + responseJson.path("entities").size());
            }
            
            // Format response (keep for backward compatibility)
            String formattedResponse = formatter.formatFusionAIResponse(responseJson);
            
            return new YelpChatResult(formattedResponse, returnedChatId, responseJson);
            
        } catch (Exception e) {
            System.err.println("[YELP_API] Exception: " + e.getMessage());
            e.printStackTrace();
            return new YelpChatResult("Error calling Yelp API: " + e.getMessage(), chatId, null);
        }
    }
    
    /**
     * Result class for Yelp API calls.
     */
    public static class YelpChatResult {
        private final String formattedResponse;
        private final String chatId;
        private final JsonNode rawResponse;
        
        public YelpChatResult(String formattedResponse, String chatId, JsonNode rawResponse) {
            this.formattedResponse = formattedResponse;
            this.chatId = chatId;
            this.rawResponse = rawResponse;
        }
        
        public String getFormattedResponse() {
            return formattedResponse;
        }
        
        public String getChatId() {
            return chatId;
        }
        
        public JsonNode getRawResponse() {
            return rawResponse;
        }
    }
}
