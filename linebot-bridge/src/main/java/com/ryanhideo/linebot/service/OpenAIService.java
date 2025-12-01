package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ryanhideo.linebot.config.OpenAIProperties;
import com.ryanhideo.linebot.config.YelpProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OpenAIService {
    
    private final OpenAIProperties openAIProperties;
    private final YelpProperties yelpProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MCP_SERVER_URL = "http://yelp-mcp:8080";
    private static final int MAX_ITERATIONS = 5;
    
    public OpenAIService(OpenAIProperties openAIProperties, YelpProperties yelpProperties) {
        this.openAIProperties = openAIProperties;
        this.yelpProperties = yelpProperties;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
    }
    
    public YelpResult callOpenAIWithYelpTool(String userQuery, String yelpConversationId) {
        List<String> messages = new ArrayList<>();
        List<List<String>> photosList = new ArrayList<>();
        String newYelpConversationId = yelpConversationId;
        
        try {
            // BYPASS OpenAI interpretation - call yelp_agent directly with user's exact query
            System.out.println("Calling yelp_agent directly with exact query: " + userQuery);
            MCPResult mcpResult = callYelpMCP(userQuery, null, null, yelpConversationId);
            
            if (mcpResult.getYelpConversationId() != null) {
                newYelpConversationId = mcpResult.getYelpConversationId();
            }
            
            // Clean up and split the formatted response into separate messages with photos
            ResponseWithPhotos response = cleanupAndSplitYelpResponse(mcpResult.getFormattedResponse());
            messages.addAll(response.messages);
            photosList.addAll(response.photos);
            
        } catch (Exception e) {
            messages.add("Error calling Yelp: " + e.getMessage());
            e.printStackTrace();
        }
        
        return new YelpResult(messages, photosList, newYelpConversationId);
    }
    
    private ResponseWithPhotos cleanupAndSplitYelpResponse(String rawResponse) {
        List<String> messages = new ArrayList<>();
        List<List<String>> photosList = new ArrayList<>();
        
        // Extract introduction/header text (everything before first "## Business")
        String header = "";
        int firstBusinessIndex = rawResponse.indexOf("## Business");
        if (firstBusinessIndex > 0) {
            header = rawResponse.substring(0, firstBusinessIndex).trim();
            // Clean up header - remove ALL technical sections
            header = header.replaceAll("(?s)^#.*?## Introduction\\s*", "");
            // Remove Chat ID section more aggressively
            header = header.replaceAll("(?m)^##\\s*Chat ID.*", "");
            header = header.replaceAll("(?m)^K_[A-Za-z0-9_-]+$", "");
            header = header.replaceAll("\\n{2,}", "\n");
            header = header.trim();
            
            if (!header.isEmpty()) {
                messages.add(header);
                photosList.add(new ArrayList<>()); // No photos for header
            }
        }
        
        // Split by business entries
        String[] businessSections = rawResponse.split("(?m)^## Business \\d+:");
        
        for (int i = 1; i < businessSections.length; i++) {
            String business = businessSections[i].trim();
            
            // Extract key information
            String name = extractField(business, "^(.+?)\\n");
            String type = extractField(business, "\\*\\*Type\\*\\*:\\s*(.+?)\\n");
            String price = extractField(business, "\\*\\*Price\\*\\*:\\s*(.+?)\\n");
            String rating = extractField(business, "\\*\\*Rating\\*\\*:\\s*(.+?)\\n");
            String location = extractField(business, "\\*\\*Location\\*\\*:\\s*(.+?)\\n");
            String url = extractField(business, "\\*\\*URL\\*\\*:\\s*\\[View on Yelp\\]\\((.+?)\\)");
            String phone = extractField(business, "\\*\\*Phone\\*\\*:\\s*(.+?)\\n");
            
            // Extract photos - get first 2
            List<String> photos = extractPhotos(business, 2);
            
            // Shorten URL
            String shortUrl = shortenYelpUrl(url);
            
            // Build compact message
            StringBuilder compactBusiness = new StringBuilder();
            if (!name.isEmpty()) {
                compactBusiness.append("📍 ").append(name).append("\n");
            }
            if (!rating.isEmpty()) {
                compactBusiness.append("⭐ ").append(rating);
            }
            if (!price.isEmpty()) {
                compactBusiness.append(" • ").append(price);
            }
            if (!type.isEmpty()) {
                compactBusiness.append("\n🍽️ ").append(type);
            }
            if (!location.isEmpty()) {
                compactBusiness.append("\n📍 ").append(location);
            }
            if (!phone.isEmpty()) {
                compactBusiness.append("\n📞 ").append(phone);
            }
            if (!shortUrl.isEmpty()) {
                compactBusiness.append("\n🔗 ").append(shortUrl);
            }
            
            String result = compactBusiness.toString().trim();
            if (!result.isEmpty()) {
                messages.add(result);
                photosList.add(photos);
            }
        }
        
        return new ResponseWithPhotos(messages, photosList);
    }
    
    private List<String> extractPhotos(String text, int maxPhotos) {
        List<String> photos = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("https://s3-media[^\\s]+\\.jpg");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find() && count < maxPhotos) {
            photos.add(matcher.group());
            count++;
        }
        return photos;
    }
    
    private String shortenYelpUrl(String url) {
        if (url.isEmpty()) return "";
        // Extract just the business ID and create a short URL
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("yelp\\.com/biz/([^?]+)");
        java.util.regex.Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return "yelp.com/biz/" + matcher.group(1);
        }
        return url;
    }
    
    // Helper class to return both messages and photos
    private static class ResponseWithPhotos {
        List<String> messages;
        List<List<String>> photos;
        
        ResponseWithPhotos(List<String> messages, List<List<String>> photos) {
            this.messages = messages;
            this.photos = photos;
        }
    }
    
    private String extractField(String text, String regex) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }
    
    // OLD METHOD - keeping for reference if needed later
    public YelpResult callOpenAIWithYelpToolOLD(String userQuery, String yelpConversationId) {
        List<String> messages = new ArrayList<>();
        String newYelpConversationId = yelpConversationId;
        
        try {
            // Define the yelp_agent tool for OpenAI
            ObjectNode toolDefinition = createYelpAgentToolDefinition();
            
            // Build the conversation with system message and user query
            ArrayNode conversationHistory = objectMapper.createArrayNode();
            
            // Add system message
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are a helpful assistant with access to Yelp business information via the yelp_agent tool. " +
                          "CRITICAL: You MUST ALWAYS use the yelp_agent tool for ANY query related to businesses, restaurants, " +
                          "locations, or local services - even for follow-up questions or location clarifications. " +
                          "The yelp_agent tool maintains conversation context via chat_id, so it can understand follow-up queries " +
                          "like 'what about in another city' or 'show me more'. NEVER answer business queries without calling the tool. " +
                          "Always include Yelp URLs when recommending businesses.");
            conversationHistory.add(systemMsg);
            
            // Add user message
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userQuery);
            conversationHistory.add(userMsg);
            
            // Iterative loop to handle tool calls
            int iteration = 0;
            boolean requiresToolCall = true;
            String finalResponse = "";
            
            while (requiresToolCall && iteration < MAX_ITERATIONS) {
                iteration++;
                System.out.println("OpenAI iteration: " + iteration);
                
                // Create OpenAI API request with tool definition
                ObjectNode openAIRequest = objectMapper.createObjectNode();
                openAIRequest.put("model", openAIProperties.getModel());
                openAIRequest.put("temperature", openAIProperties.getTemperature());
                
                // Use conversation history directly as messages
                openAIRequest.set("messages", conversationHistory);
                
                // Add tools
                ArrayNode toolsArray = objectMapper.createArrayNode();
                toolsArray.add(toolDefinition);
                openAIRequest.set("tools", toolsArray);
                openAIRequest.put("tool_choice", "auto");
                
                // Call OpenAI API
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(openAIProperties.getApiKey());
                
                HttpEntity<String> entity = new HttpEntity<>(openAIRequest.toString(), headers);
                ResponseEntity<String> response = restTemplate.exchange(
                    OPENAI_API_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
                );
                
                if (!response.getStatusCode().is2xxSuccessful()) {
                    messages.add("OpenAI API error: " + response.getStatusCodeValue());
                    break;
                }
                
                JsonNode responseBody = objectMapper.readTree(response.getBody());
                JsonNode choice = responseBody.path("choices").get(0);
                JsonNode message = choice.path("message");
                
                // Check if OpenAI wants to call a tool
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isMissingNode() && toolCalls.isArray() && toolCalls.size() > 0) {
                    // Add assistant's message with tool_calls to history
                    ObjectNode assistantMsg = objectMapper.createObjectNode();
                    assistantMsg.put("role", "assistant");
                    String content = message.path("content").asText("");
                    if (content != null && !content.isEmpty()) {
                        assistantMsg.put("content", content);
                    }
                    assistantMsg.set("tool_calls", toolCalls);
                    conversationHistory.add(assistantMsg);
                    
                    // Execute each tool call
                    for (JsonNode toolCall : toolCalls) {
                        String toolCallId = toolCall.path("id").asText();
                        String functionName = toolCall.path("function").path("name").asText();
                        String argumentsJson = toolCall.path("function").path("arguments").asText();
                        
                        System.out.println("OpenAI requested tool: " + functionName);
                        System.out.println("Tool arguments: " + argumentsJson);
                        
                        if ("yelp_agent".equals(functionName)) {
                            // Parse arguments
                            JsonNode args = objectMapper.readTree(argumentsJson);
                            String query = args.path("natural_language_query").asText();
                            Double latitude = args.has("search_latitude") ? args.path("search_latitude").asDouble() : null;
                            Double longitude = args.has("search_longitude") ? args.path("search_longitude").asDouble() : null;
                            
                            // Call yelp-mcp service with yelpConversationId
                            OpenAIService.MCPResult mcpResult = callYelpMCP(query, latitude, longitude, yelpConversationId);
                            String toolResult = mcpResult.getFormattedResponse();
                            
                            // Update yelpConversationId if returned
                            if (mcpResult.getYelpConversationId() != null) {
                                newYelpConversationId = mcpResult.getYelpConversationId();
                                System.out.println("Updated yelpConversationId from MCP: " + newYelpConversationId);
                            }
                            
                            // Add tool result to conversation
                            ObjectNode toolMsg = objectMapper.createObjectNode();
                            toolMsg.put("role", "tool");
                            toolMsg.put("tool_call_id", toolCallId);
                            toolMsg.put("content", toolResult);
                            conversationHistory.add(toolMsg);
                        }
                    }
                } else {
                    // No more tool calls, get final response
                    requiresToolCall = false;
                    finalResponse = message.path("content").asText();
                    messages.add(finalResponse);
                }
            }
            
            if (iteration >= MAX_ITERATIONS) {
                messages.add("Maximum iterations reached. Please try again.");
            }
            
        } catch (Exception e) {
            messages.add("Error calling OpenAI with Yelp tool: " + e.getMessage());
            e.printStackTrace();
        }
        
        List<List<String>> emptyPhotos = new ArrayList<>();
        emptyPhotos.add(new ArrayList<>());
        return new YelpResult(messages, emptyPhotos, newYelpConversationId);
    }
    
    private ObjectNode createYelpAgentToolDefinition() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", "yelp_agent");
        function.put("description", 
            "Intelligent Yelp business agent designed for agent-to-agent communication. " +
            "Handles any natural language request about local businesses. " +
            "Returns both natural language responses and structured business data. " +
            "CRITICAL: When recommending businesses, you MUST ALWAYS include the Yelp " +
            "URL from the structured data to ensure users can view the business on Yelp directly. " +
            "The yelp_agent maintains conversation context, so pass the user's EXACT query without modification. " +
            "Capabilities include: business search, detailed questions, comparisons, itinerary planning, " +
            "reservation booking exclusively through the Yelp Reservations platform at participating " +
            "restaurants, and any other business-related analysis or recommendations."
        );
        
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode queryProp = objectMapper.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", "Pass the user's EXACT query without any modification or interpretation. " +
                                      "The yelp_agent will handle context and interpretation internally.");
        properties.set("natural_language_query", queryProp);
        
        ObjectNode latProp = objectMapper.createObjectNode();
        latProp.put("type", "number");
        latProp.put("description", "Optional latitude coordinate for precise location-based searches");
        properties.set("search_latitude", latProp);
        
        ObjectNode lonProp = objectMapper.createObjectNode();
        lonProp.put("type", "number");
        lonProp.put("description", "Optional longitude coordinate for precise location-based searches");
        properties.set("search_longitude", lonProp);
        
        parameters.set("properties", properties);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("natural_language_query");
        parameters.set("required", required);
        
        function.set("parameters", parameters);
        tool.set("function", function);
        
        return tool;
    }
    
    private MCPResult callYelpMCP(String query, Double latitude, Double longitude, String yelpConversationId) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("natural_language_query", query);
            
            // Only include coordinates if explicitly provided by OpenAI
            if (latitude != null && longitude != null) {
                request.put("search_latitude", latitude);
                request.put("search_longitude", longitude);
            }
            // Otherwise, let yelp-mcp extract location from the natural language query
            
            // Include yelpConversationId if available for conversation continuity
            if (yelpConversationId != null && !yelpConversationId.isEmpty()) {
                request.put("chat_id", yelpConversationId);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<String> entity = new HttpEntity<>(request.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                MCP_SERVER_URL + "/yelp_agent",
                HttpMethod.POST,
                entity,
                String.class
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                // Parse response to extract formatted_response and chat_id
                JsonNode responseJson = objectMapper.readTree(response.getBody());
                String formattedResponse = responseJson.path("formatted_response").asText();
                String chatId = responseJson.has("chat_id") ? responseJson.path("chat_id").asText(null) : null;
                
                return new MCPResult(formattedResponse, chatId);
            } else {
                String errorJson = "{\"error\": \"MCP server returned error: " + response.getStatusCodeValue() + "\"}";
                return new MCPResult(errorJson, null);
            }
            
        } catch (Exception e) {
            System.err.println("Error calling yelp-mcp: " + e.getMessage());
            e.printStackTrace();
            String errorJson = "{\"error\": \"Failed to call yelp-mcp: " + e.getMessage() + "\"}";
            return new MCPResult(errorJson, null);
        }
    }
    
    public static class YelpResult {
        private final List<String> messages;
        private final List<List<String>> photos;
        private final String yelpConversationId;
        
        public YelpResult(List<String> messages, List<List<String>> photos, String yelpConversationId) {
            this.messages = messages;
            this.photos = photos;
            this.yelpConversationId = yelpConversationId;
        }
        
        public List<String> getMessages() {
            return messages;
        }
        
        public List<List<String>> getPhotos() {
            return photos;
        }
        
        public String getYelpConversationId() {
            return yelpConversationId;
        }
    }
    
    private static class MCPResult {
        private final String formattedResponse;
        private final String yelpConversationId;
        
        public MCPResult(String formattedResponse, String yelpConversationId) {
            this.formattedResponse = formattedResponse;
            this.yelpConversationId = yelpConversationId;
        }
        
        public String getFormattedResponse() {
            return formattedResponse;
        }
        
        public String getYelpConversationId() {
            return yelpConversationId;
        }
    }
}
