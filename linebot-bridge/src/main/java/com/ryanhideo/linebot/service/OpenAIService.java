package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ryanhideo.linebot.config.OpenAIProperties;
import com.ryanhideo.linebot.model.RestaurantData;
import com.ryanhideo.linebot.config.YelpProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OpenAIService {
    
    private final OpenAIProperties openAIProperties;
    private final YelpProperties yelpProperties;
    private final YelpApiService yelpApiService;
    private final ConversationAggregatesService conversationAggregatesService;
    private final UserPreferencesService userPreferencesService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    
    // TODO: Update these URLs once Node/TypeScript MCP servers are implemented
    private static final String DB_MCP_URL = "http://db-mcp:8080";
    private static final String CHAT_HISTORY_MCP_URL = "http://chat-history-mcp:8080";
    
    private static final int MAX_ITERATIONS = 5;
    
    public OpenAIService(OpenAIProperties openAIProperties, YelpProperties yelpProperties, 
                        YelpApiService yelpApiService, ConversationAggregatesService conversationAggregatesService,
                        UserPreferencesService userPreferencesService) {
        this.openAIProperties = openAIProperties;
        this.yelpProperties = yelpProperties;
        this.yelpApiService = yelpApiService;
        this.conversationAggregatesService = conversationAggregatesService;
        this.userPreferencesService = userPreferencesService;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
    }
    
    /**
     * HYBRID APPROACH: Main entry point for Yelp queries.
     * 
     * Flow:
     * 1. Gather recent chat history (existing flow)
     * 2. Call OpenAI with DB MCP tools to gather context (user prefs, specific history search)
     * 3. Build enhanced query combining all context
     * 4. Direct call to Yelp Fusion AI API via Java service
     * 
     * @param userQuery The user's original query
     * @param yelpConversationId The Yelp conversation ID for context
     * @param chatHistory Recent chat history (from existing flow)
     * @param lineConversationId The LINE conversation ID for getting all members' preferences
     * @return YelpResult with messages, photos, and updated conversation ID
     */
    public YelpResult callOpenAIWithYelpTool(String userQuery, String yelpConversationId, String chatHistory, String lineConversationId) {
        List<String> messages = new ArrayList<>();
        List<List<String>> photosList = new ArrayList<>();
        List<RestaurantData> restaurants = new ArrayList<>();
        String newYelpConversationId = yelpConversationId;
        
        try {
            // Step 1: Gather context from DB MCPs using OpenAI (user preferences, specific history)
            ContextResult context = gatherContextWithOpenAI(userQuery, lineConversationId, yelpConversationId);
            
            // Step 2: Check if this is a recall query with history results
            if (context.hasHistorySearch() && context.getHistorySearchResult() != null) {
                // For recall queries, extract and return the found restaurants directly
                String recallResponse = formatRecallResponse(context.getHistorySearchResult(), userQuery);
                if (recallResponse != null) {
                    System.out.println("Answering recall query from chat history (no Yelp call needed)");
                    messages.add(recallResponse);
                    photosList.add(new ArrayList<>()); // No photos for recall responses
                    return new YelpResult(messages, photosList, yelpConversationId);
                }
            }
            
            // Step 3: Build enhanced query with all context for new search
            String enhancedQuery = buildEnhancedQuery(userQuery, context, chatHistory);
            
            System.out.println("Enhanced query for Yelp API:\n" + enhancedQuery);
            
            // Step 4: Direct call to Yelp Fusion AI API via Java service
            // Let Yelp ask for location naturally in conversation
            YelpApiService.YelpChatResult yelpResult = yelpApiService.queryYelpAI(
                enhancedQuery,
                yelpConversationId,
                null,  // Let Yelp extract location from query or ask user
                null   // Let Yelp extract location from query or ask user
            );
            
            if (yelpResult.getChatId() != null) {
                newYelpConversationId = yelpResult.getChatId();
            }
            
            // Step 5: Extract structured restaurant data from raw response
            if (yelpResult.getRawResponse() != null) {
                System.out.println("[EXTRACT] Raw response exists, extracting restaurant data...");
                YelpResponseFormatter formatter = new YelpResponseFormatter();
                restaurants = formatter.extractRestaurantData(yelpResult.getRawResponse());
                System.out.println("[EXTRACT] Extracted " + restaurants.size() + " restaurants");
                
                // Step 5.5: Enhance reasoning with both user-set preferences AND learned preferences
                if (!restaurants.isEmpty()) {
                    // For DMs, userId is the same as lineConversationId
                    // For groups, we'll use the first user's preferences as representative
                    enhanceReasoningWithPreferences(restaurants, lineConversationId, lineConversationId);
                }
            } else {
                System.out.println("[EXTRACT] No raw response available for restaurant extraction");
            }
            
            // Step 6: Clean up and split the formatted response into separate messages with photos
            ResponseWithPhotos response = cleanupAndSplitYelpResponse(yelpResult.getFormattedResponse());
            messages.addAll(response.messages);
            photosList.addAll(response.photos);
            
        } catch (Exception e) {
            messages.add("Error calling Yelp: " + e.getMessage());
            e.printStackTrace();
        }
        
        return new YelpResult(messages, photosList, newYelpConversationId, restaurants);
    }
    
    /**
     * BACKWARD COMPATIBILITY: Overload without userId parameter.
     * Uses empty context gathering (no preferences loaded).
     */
    public YelpResult callOpenAIWithYelpTool(String userQuery, String yelpConversationId, String chatHistory) {
        return callOpenAIWithYelpTool(userQuery, yelpConversationId, chatHistory, null);
    }
    
    private ResponseWithPhotos cleanupAndSplitYelpResponse(String rawResponse) {
        List<String> messages = new ArrayList<>();
        List<List<String>> photosList = new ArrayList<>();
        
        // Extract introduction/header text (everything before first "## Business")
        String header = "";
        int firstBusinessIndex = rawResponse.indexOf("## Business");
        if (firstBusinessIndex > 0) {
            // Businesses found - extract intro before them
            header = rawResponse.substring(0, firstBusinessIndex).trim();
        } else if (firstBusinessIndex == -1) {
            // No businesses - use entire response as intro
            header = rawResponse;
        }
        
        if (!header.isEmpty()) {
            // Clean up header - remove ALL technical sections
            header = header.replaceAll("(?s)^#.*?## Introduction\\s*", "");
            // Remove Chat ID section more aggressively
            header = header.replaceAll("(?m)^##\\s*Chat ID.*", "");
            // Remove chat IDs - more aggressive patterns to catch all formats
            // Format: Jc71Tdh_mBpWdsG19zKbnQ (22 chars, alphanumeric + _ + -)
            header = header.replaceAll("(?m)^\\s*[A-Za-z][A-Za-z0-9_-]{15,}\\s*$", "");  // Remove standalone chat IDs on own line
            header = header.replaceAll("(?m)\\n\\s*[A-Za-z][A-Za-z0-9_-]{15,}\\s*$", "");  // Remove chat IDs after newline at end
            header = header.replaceAll("\\s+[A-Za-z][A-Za-z0-9_-]{21}\\s*$", "");  // Remove 22-char chat IDs at end (typical length)
            header = header.replaceAll("\\n{3,}", "\n\n");  // Clean up excessive newlines
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
    
    /**
     * Enhance restaurant reasoning with BOTH user-set preferences AND learned preferences from graph DB
     */
    private void enhanceReasoningWithPreferences(List<RestaurantData> restaurants, String lineConversationId, String userId) {
        try {
            // Get user-set preferences (from /diet, /allergies, /favorites, /price commands)
            UserPreferencesService.UserPreferences userPrefs = null;
            if (userPreferencesService != null) {
                userPrefs = userPreferencesService.getUserPreferences(userId);
            }
            
            // Get learned preferences from graph database (from likes/dislikes)
            ConversationAggregatesService.ConversationAggregates aggregates = null;
            if (conversationAggregatesService != null) {
                aggregates = conversationAggregatesService.getConversationAggregates(lineConversationId);
            }
            
            if (userPrefs == null && aggregates == null) {
                System.out.println("[REASONING] No preferences available (neither user-set nor learned)");
                return;
            }
            
            // Extract user-set preferences
            List<String> favoriteCuisines = new ArrayList<>();
            Integer preferredPrice = null;
            List<String> dietRestrictions = new ArrayList<>();
            List<String> allergies = new ArrayList<>();
            
            if (userPrefs != null) {
                if (userPrefs.getFavoriteCuisines() != null) {
                    favoriteCuisines.addAll(List.of(userPrefs.getFavoriteCuisines()));
                }
                preferredPrice = userPrefs.getPriceRange();
                if (userPrefs.getDiet() != null) {
                    dietRestrictions.addAll(List.of(userPrefs.getDiet()));
                }
                if (userPrefs.getAllergies() != null) {
                    allergies.addAll(List.of(userPrefs.getAllergies()));
                }
            }
            
            // Extract learned preferences from graph DB
            List<String> likedCuisines = new ArrayList<>();
            Integer learnedPrice = null;
            
            if (aggregates != null) {
                if (aggregates.getTopCuisines() != null) {
                    likedCuisines.addAll(aggregates.getTopCuisines());
                }
                learnedPrice = aggregates.getAvgPrice();
            }
            
            System.out.println("[REASONING] User-set: favorites=" + favoriteCuisines + ", price=" + preferredPrice + 
                             ", diet=" + dietRestrictions + ", allergies=" + allergies);
            System.out.println("[REASONING] Learned: liked=" + likedCuisines + ", avgPrice=" + learnedPrice);
            
            for (RestaurantData restaurant : restaurants) {
                String originalReasoning = restaurant.getReasoning();
                if (originalReasoning == null || originalReasoning.equals("Great option based on your preferences!")) {
                    originalReasoning = "";
                }
                
                StringBuilder enhanced = new StringBuilder();
                
                // Check cuisine matches (prioritize user-set favorites, then learned)
                String cuisine = restaurant.getCuisine();
                if (cuisine != null && !cuisine.isEmpty()) {
                    String[] restaurantCuisines = cuisine.toLowerCase().split(",\\s*");
                    
                    // Check against user-set favorite cuisines
                    boolean matchedFavorite = false;
                    for (String rc : restaurantCuisines) {
                        for (String fav : favoriteCuisines) {
                            String favLower = fav.toLowerCase();
                            if (rc.contains(favLower) || favLower.contains(rc)) {
                                enhanced.append("✓ Matches your favorite ").append(fav).append(" cuisine! ");
                                matchedFavorite = true;
                                break;
                            }
                        }
                        if (matchedFavorite) break;
                    }
                    
                    // Check against learned liked cuisines (if no favorite match)
                    if (!matchedFavorite && !likedCuisines.isEmpty()) {
                        for (String rc : restaurantCuisines) {
                            for (String liked : likedCuisines) {
                                String likedLower = liked.toLowerCase();
                                if (rc.contains(likedLower) || likedLower.contains(rc)) {
                                    enhanced.append("✓ You've liked ").append(liked).append(" before! ");
                                    break;
                                }
                            }
                        }
                    }
                }
                
                // Check price level (prioritize user-set, then learned)
                String price = restaurant.getPrice();
                if (price != null && !price.isEmpty()) {
                    int priceLevel = price.length();
                    Integer targetPrice = preferredPrice != null ? preferredPrice : learnedPrice;
                    
                    if (targetPrice != null && targetPrice > 0) {
                        if (priceLevel == targetPrice) {
                            String source = preferredPrice != null ? "preferred" : "typical";
                            enhanced.append("✓ Matches your ").append(source).append(" $".repeat(targetPrice)).append(" range. ");
                        } else if (priceLevel < targetPrice) {
                            enhanced.append("More affordable than your usual spots. ");
                        } else if (priceLevel == targetPrice + 1) {
                            enhanced.append("Slightly pricier, but worth it! ");
                        }
                    }
                }
                
                // Add dietary/allergy notes if applicable
                if (!dietRestrictions.isEmpty()) {
                    enhanced.append("Dietary: ").append(String.join(", ", dietRestrictions)).append(". ");
                }
                
                // Add original reasoning
                if (!originalReasoning.isEmpty()) {
                    if (enhanced.length() > 0) enhanced.append("\n");
                    enhanced.append(originalReasoning);
                } else if (enhanced.length() == 0) {
                    enhanced.append("Highly rated choice in the area!");
                }
                
                String finalReasoning = enhanced.toString().trim();
                restaurant.setReasoning(finalReasoning);
                System.out.println("[REASONING] Enhanced for " + restaurant.getName() + ": " + finalReasoning);
            }
            
            System.out.println("[REASONING] Enhanced reasoning for " + restaurants.size() + " restaurants");
        } catch (Exception e) {
            System.err.println("[REASONING] Error enhancing reasoning: " + e.getMessage());
            e.printStackTrace();
            // Don't fail the whole operation, just keep original reasoning
        }
    }
    
    // ============================================================================
    // HYBRID APPROACH: OpenAI for DB Context Gathering + Direct Yelp MCP Call
    // ============================================================================
    
    /**
     * Gathers context from database MCPs using OpenAI's tool calling.
     * Flow: OpenAI decides which tools to call based on user query:
     * - get_user_preferences: Get dietary restrictions, allergies, price pref, favorite cuisines for all conversation members
     * - search_chat_history: Semantic search for specific past conversations
     * 
     * @param userQuery The user's original query
     * @param lineConversationId The LINE conversation ID to get all members' preferences
     * @param yelpConversationId The conversation ID for chat history search
     * @return ContextResult containing preferences and relevant history
     */
    private ContextResult gatherContextWithOpenAI(String userQuery, String lineConversationId, String yelpConversationId) {
        ContextResult context = new ContextResult();
        
        try {
            // Build conversation with system message
            ArrayNode messages = objectMapper.createArrayNode();
            
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", 
                "You are a context gathering assistant for a restaurant recommendation system. " +
                "Your job is to gather context to personalize restaurant recommendations. " +
                "ALWAYS call get_user_preferences for ANY restaurant query to check dietary restrictions, allergies, price preferences, and favorite cuisines. " +
                "Also call search_chat_history if the user references past conversations. When searching history: " +
                "- Extract KEY KEYWORDS from the user's query (e.g., 'recommend', 'date', 'San Diego') " +
                "- Use simple search terms, not full sentences " +
                "- Focus on nouns and action verbs " +
                "Examples: 'what did you recommend yesterday?' -> query: 'recommend' | 'that sushi place' -> query: 'sushi' " +
                "After gathering context, return a brief JSON summary of what you found.");
            messages.add(systemMsg);
            
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userQuery);
            messages.add(userMsg);
            
            // Create OpenAI request with DB MCP tools
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", openAIProperties.getModel());
            request.put("temperature", 0.3); // Lower temperature for context gathering
            request.set("messages", messages);
            
            // Add tool definitions for DB MCPs
            ArrayNode tools = objectMapper.createArrayNode();
            tools.add(createUserPreferencesTool());
            tools.add(createChatHistorySearchTool());
            tools.add(createConversationContextTool());
            request.set("tools", tools);
            request.put("tool_choice", "auto");
            
            // Iterative tool calling loop
            int iteration = 0;
            while (iteration < MAX_ITERATIONS) {
                iteration++;
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(openAIProperties.getApiKey());
                
                HttpEntity<String> entity = new HttpEntity<>(request.toString(), headers);
                ResponseEntity<String> response = restTemplate.exchange(
                    OPENAI_API_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
                );
                
                if (!response.getStatusCode().is2xxSuccessful()) {
                    System.err.println("OpenAI context gathering error: " + response.getStatusCodeValue());
                    break;
                }
                
                JsonNode responseBody = objectMapper.readTree(response.getBody());
                JsonNode choice = responseBody.path("choices").get(0);
                JsonNode message = choice.path("message");
                
                // Check for tool calls
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isMissingNode() && toolCalls.isArray() && toolCalls.size() > 0) {
                    System.out.println("[OpenAI] 🔧 OpenAI requested " + toolCalls.size() + " tool call(s)");
                    
                    // Add assistant message to history
                    messages.add(message);
                    
                    // Execute tool calls
                    for (JsonNode toolCall : toolCalls) {
                        String toolCallId = toolCall.path("id").asText();
                        String functionName = toolCall.path("function").path("name").asText();
                        String argumentsJson = toolCall.path("function").path("arguments").asText();
                        JsonNode args = objectMapper.readTree(argumentsJson);
                        
                        System.out.println("[OpenAI] 🔧 Tool call: " + functionName + " with args: " + argumentsJson);
                        
                        String toolResult = "";
                        
                        if ("get_user_preferences".equals(functionName)) {
                            // Call DB MCP to get all conversation members' preferences
                            System.out.println("[OpenAI] 📞 Calling get_user_preferences MCP for conversation: " + lineConversationId);
                            toolResult = callUserPreferencesMCP(lineConversationId);
                            context.setHasPreferences(true);
                            context.setPreferencesJson(toolResult);
                            
                        } else if ("search_chat_history".equals(functionName)) {
                            // Call Chat History MCP for semantic search
                            String searchQuery = args.path("query").asText();
                            // Use LINE conversation ID since messages are stored with lineconversationid
                            toolResult = callChatHistoryMCP(lineConversationId, searchQuery);
                            context.setHasHistorySearch(true);
                            context.setHistorySearchResult(toolResult);
                            
                        } else if ("get_conversation_context".equals(functionName)) {
                            // Call Conversation Context MCP for group aggregate preferences
                            System.out.println("[OpenAI] 📞 Calling get_conversation_context MCP for conversation: " + lineConversationId);
                            toolResult = callConversationContextMCP(lineConversationId);
                            // Add to context summary (could extend ContextResult if needed)
                        }
                        
                        // Add tool result to conversation
                        ObjectNode toolMsg = objectMapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", toolCallId);
                        toolMsg.put("content", toolResult);
                        messages.add(toolMsg);
                    }
                    
                    // Update request with new messages for next iteration
                    request.set("messages", messages);
                    
                } else {
                    // No more tool calls, get final summary
                    String finalContent = message.path("content").asText("");
                    context.setSummary(finalContent);
                    break;
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error gathering context with OpenAI: " + e.getMessage());
            e.printStackTrace();
        }
        
        return context;
    }
    
    /**
     * Creates tool definition for get_user_preferences MCP.
     * This will be implemented as a Node/TypeScript MCP server.
     */
    private ObjectNode createUserPreferencesTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", "get_user_preferences");
        function.put("description", 
            "Retrieves food preferences for ALL members in the conversation, including dietary restrictions (vegan, gluten-free, etc.), " +
            "allergies, price range preference (1-4), and favorite cuisines. " +
            "Use this when the user is asking for restaurant recommendations to personalize results for everyone in the group.");
        
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", objectMapper.createObjectNode());
        parameters.set("required", objectMapper.createArrayNode());
        
        function.set("parameters", parameters);
        tool.set("function", function);
        
        return tool;
    }
    
    /**
     * Creates tool definition for search_chat_history MCP.
     * This will be implemented as a Node/TypeScript MCP server.
     */
    private ObjectNode createChatHistorySearchTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", "search_chat_history");
        function.put("description", 
            "Searches through past conversation history for specific information. " +
            "Use this when user references past conversations like: " +
            "'what did you recommend yesterday?', 'that place you showed me', 'like last time', etc. " +
            "Returns relevant past messages and recommendations.");
        
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode queryProp = objectMapper.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", 
            "Search keywords to find relevant past messages (space-separated). " +
            "Extract 2-4 KEY KEYWORDS that represent the core concept. " +
            "Examples: " +
            "  'what were the sushi places?' → 'sushi places' or 'sushi restaurant'" +
            "  'date activities yesterday' → 'date activities' or 'activities things'" +
            "  'that Italian place' → 'italian restaurant'" +
            "Use nouns and specific terms. Multiple keywords give better recall.");
        properties.set("query", queryProp);
        
        parameters.set("properties", properties);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("query");
        parameters.set("required", required);
        
        function.set("parameters", parameters);
        tool.set("function", function);
        
        return tool;
    }
    
    /**
     * Calls the User Preferences MCP server via stdio (Node.js MCP SDK).
     * 
     * The MCP server runs as a subprocess and communicates via JSON-RPC over stdio.
     * Request: { "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": "get_user_preferences", "arguments": { "conversationId": "C123..." } } }
     * Response: { "found": true, "memberCount": 2, "preferences": { "diet": [...], "allergies": [...], "priceRange": 1, "favoriteCuisines": [...] } }
     */
    private String callUserPreferencesMCP(String conversationId) {
        System.out.println("[MCP] ========================================");
        System.out.println("[MCP] Calling User Preferences MCP for conversationId: " + conversationId);
        System.out.println("[MCP] ========================================");
        
        try {
            // Start MCP process
            ProcessBuilder pb = new ProcessBuilder("node", "/app/user-prefs-mcp/index.js");
            pb.environment().put("DB_HOST", "linebot-db");
            pb.environment().put("DB_PORT", "5432");
            pb.environment().put("DB_NAME", System.getenv("POSTGRES_DB"));
            pb.environment().put("DB_USER", System.getenv("POSTGRES_USER"));
            pb.environment().put("DB_PASSWORD", System.getenv("POSTGRES_PASSWORD"));
            
            System.out.println("[MCP] Starting Node.js MCP process...");
            Process process = pb.start();
            
            // Write JSON-RPC request
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", 1);
            request.put("method", "tools/call");
            
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", "get_user_preferences");
            
            ObjectNode args = objectMapper.createObjectNode();
            args.put("conversationId", conversationId);
            params.set("arguments", args);
            
            request.set("params", params);
            
            System.out.println("[MCP] Sending JSON-RPC request: " + request.toString());
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                writer.write(request.toString());
                writer.newLine();
                writer.flush();
            }
            
            // Read JSON-RPC response
            String response;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                response = reader.readLine();
            }
            
            System.out.println("[MCP] Received response: " + response);
            
            process.destroy();
            
            if (response != null) {
                JsonNode responseNode = objectMapper.readTree(response);
                JsonNode result = responseNode.path("result");
                JsonNode content = result.path("content").get(0);
                String prefsJson = content.path("text").asText();
                System.out.println("[MCP] User preferences retrieved: " + prefsJson);
                return prefsJson;
            }
            
        } catch (Exception e) {
            System.err.println("[MCP] ❌ Error calling user preferences MCP: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Fallback to empty preferences
        return "{\"found\": false, \"preferences\": {\"diet\": [], \"allergies\": [], \"priceRange\": null, \"favoriteCuisines\": []}}";
    }
    
    /**
     * Calls the Chat History MCP server for semantic search via stdio (Node.js MCP SDK).
     * 
     * The MCP server runs as a subprocess and communicates via JSON-RPC over stdio.
     * Request: { "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": "search_chat_history", "arguments": { "conversationId": "C123...", "query": "restaurants" } } }
     * Response: { "found": true, "resultCount": 3, "results": [...] }
     */
    private String callChatHistoryMCP(String conversationId, String searchQuery) {
        System.out.println("[MCP] ========================================");
        System.out.println("[MCP] Calling Chat History MCP");
        System.out.println("[MCP] ConversationId: " + conversationId);
        System.out.println("[MCP] Search Query: " + searchQuery);
        System.out.println("[MCP] ========================================");
        
        try {
            // Start MCP process
            ProcessBuilder pb = new ProcessBuilder("node", "/app/chat-history-mcp/index.js");
            pb.environment().put("DB_HOST", "linebot-db");
            pb.environment().put("DB_PORT", "5432");
            pb.environment().put("DB_NAME", System.getenv("POSTGRES_DB"));
            pb.environment().put("DB_USER", System.getenv("POSTGRES_USER"));
            pb.environment().put("DB_PASSWORD", System.getenv("POSTGRES_PASSWORD"));
            
            System.out.println("[MCP] Starting Node.js MCP process...");
            Process process = pb.start();
            
            // Write JSON-RPC request
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", 1);
            request.put("method", "tools/call");
            
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", "search_chat_history");
            
            ObjectNode args = objectMapper.createObjectNode();
            args.put("conversationId", conversationId);
            args.put("query", searchQuery);
            args.put("limit", 5);
            params.set("arguments", args);
            
            request.set("params", params);
            
            System.out.println("[MCP] Sending JSON-RPC request: " + request.toString());
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                writer.write(request.toString());
                writer.newLine();
                writer.flush();
            }
            
            // Read JSON-RPC response
            String response;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                response = reader.readLine();
            }
            
            System.out.println("[MCP] Received response: " + response);
            
            process.destroy();
            
            if (response != null) {
                JsonNode responseNode = objectMapper.readTree(response);
                JsonNode result = responseNode.path("result");
                JsonNode content = result.path("content").get(0);
                String historyJson = content.path("text").asText();
                System.out.println("[MCP] Chat history retrieved: " + historyJson);
                return historyJson;
            }
            
        } catch (Exception e) {
            System.err.println("[MCP] ❌ Error calling chat history MCP: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Fallback to empty results
        return "{\"found\": false, \"results\": []}";
    }
    
    /**
     * Creates tool definition for get_conversation_context MCP.
     */
    private ObjectNode createConversationContextTool() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", "get_conversation_context");
        function.put("description", 
            "Retrieves conversation-level aggregate preferences from Neo4j graph data. " +
            "Returns top cuisines liked by members, cuisines to avoid (strong dislikes), and average price level. " +
            "Use this to understand group dining preferences and patterns based on past likes/dislikes.");
        
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", objectMapper.createObjectNode());
        parameters.set("required", objectMapper.createArrayNode());
        
        function.set("parameters", parameters);
        tool.set("function", function);
        
        return tool;
    }
    
    /**
     * Calls the Conversation Context MCP server via stdio (Node.js MCP SDK).
     * 
     * Request: { "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": "get_conversation_context", "arguments": { "conversationId": "C123..." } } }
     * Response: { "found": true, "topCuisines": ["Italian", "Japanese"], "strongAvoids": ["Indian"], "avgPrice": 2, "contextString": "..." }
     */
    private String callConversationContextMCP(String conversationId) {
        System.out.println("[MCP] ========================================");
        System.out.println("[MCP] Calling Conversation Context MCP for conversationId: " + conversationId);
        System.out.println("[MCP] ========================================");
        
        try {
            // Start MCP process
            ProcessBuilder pb = new ProcessBuilder("node", "/app/conversation-context-mcp/index.js");
            pb.environment().put("DB_HOST", "linebot-db");
            pb.environment().put("DB_PORT", "5432");
            pb.environment().put("DB_NAME", System.getenv("POSTGRES_DB"));
            pb.environment().put("DB_USER", System.getenv("POSTGRES_USER"));
            pb.environment().put("DB_PASSWORD", System.getenv("POSTGRES_PASSWORD"));
            
            System.out.println("[MCP] Starting Node.js MCP process...");
            Process process = pb.start();
            
            // Write JSON-RPC request
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", 1);
            request.put("method", "tools/call");
            
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", "get_conversation_context");
            
            ObjectNode args = objectMapper.createObjectNode();
            args.put("conversationId", conversationId);
            params.set("arguments", args);
            
            request.set("params", params);
            
            System.out.println("[MCP] Sending JSON-RPC request: " + request.toString());
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                writer.write(request.toString());
                writer.newLine();
                writer.flush();
            }
            
            // Read JSON-RPC response
            String response;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                response = reader.readLine();
            }
            
            System.out.println("[MCP] Received response: " + response);
            
            process.destroy();
            
            if (response != null) {
                JsonNode responseNode = objectMapper.readTree(response);
                JsonNode result = responseNode.path("result");
                JsonNode content = result.path("content").get(0);
                String contextJson = content.path("text").asText();
                System.out.println("[MCP] Conversation context retrieved: " + contextJson);
                return contextJson;
            }
            
        } catch (Exception e) {
            System.err.println("[MCP] ❌ Error calling conversation context MCP: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Fallback to empty context
        return "{\"found\": false, \"topCuisines\": [], \"strongAvoids\": [], \"avgPrice\": null}";
    }
    
    /**
     * Builds an enhanced query for Yelp MCP by combining:
     * 1. Original user query
     * 2. User preferences from DB MCP (if gathered)
     * 3. Relevant chat history (if searched)
     * 4. Recent conversation context (existing flow)
     * 
     * @param originalQuery The user's original query
     * @param context Context gathered from OpenAI + DB MCPs
     * @param recentHistory Recent chat history from existing flow
     * @return Enhanced query string for Yelp MCP
     */
    private String buildEnhancedQuery(String originalQuery, ContextResult context, String recentHistory) {
        StringBuilder enhanced = new StringBuilder();
        
        // Add user preferences if available
        if (context.hasPreferences() && context.getPreferencesJson() != null) {
            try {
                JsonNode prefs = objectMapper.readTree(context.getPreferencesJson());
                
                List<String> prefParts = new ArrayList<>();
                
                // Get preferences object
                JsonNode prefsData = prefs.path("preferences");
                
                // Diet restrictions
                JsonNode diet = prefsData.path("diet");
                if (diet.isArray() && diet.size() > 0) {
                    List<String> dietList = new ArrayList<>();
                    diet.forEach(d -> dietList.add(d.asText()));
                    prefParts.add("dietary restrictions: " + String.join(", ", dietList));
                }
                
                // Allergies
                JsonNode allergies = prefsData.path("allergies");
                if (allergies.isArray() && allergies.size() > 0) {
                    List<String> allergyList = new ArrayList<>();
                    allergies.forEach(a -> allergyList.add(a.asText()));
                    prefParts.add("allergies: " + String.join(", ", allergyList));
                }
                
                // Price range (camelCase from MCP)
                JsonNode priceRange = prefsData.path("priceRange");
                if (!priceRange.isMissingNode() && !priceRange.isNull()) {
                    int priceLevel = priceRange.asInt();
                    prefParts.add("price preference: " + "$".repeat(priceLevel) + " (budget-friendly)");
                }
                
                // Favorite cuisines (camelCase from MCP)
                JsonNode favorites = prefsData.path("favoriteCuisines");
                if (favorites.isArray() && favorites.size() > 0) {
                    List<String> favList = new ArrayList<>();
                    favorites.forEach(f -> favList.add(f.asText()));
                    prefParts.add("favorite cuisines: " + String.join(", ", favList));
                }
                
                if (!prefParts.isEmpty()) {
                    enhanced.append("User preferences: ").append(String.join("; ", prefParts)).append("\n\n");
                }
                
            } catch (Exception e) {
                System.err.println("Error parsing preferences JSON: " + e.getMessage());
            }
        }
        
        // Add specific history search results if available (SUMMARIZED to stay under Yelp limits)
        if (context.hasHistorySearch() && context.getHistorySearchResult() != null) {
            try {
                JsonNode historyResult = objectMapper.readTree(context.getHistorySearchResult());
                boolean found = historyResult.path("found").asBoolean(false);
                
                if (found) {
                    JsonNode results = historyResult.path("results");
                    if (results.isArray() && results.size() > 0) {
                        List<String> restaurantNames = new ArrayList<>();
                        
                        for (JsonNode msg : results) {
                            String content = msg.path("content").asText();
                            
                            // Skip help messages and commands
                            if (content.contains("Commands:") || content.contains("/help") || 
                                content.contains("/ping") || content.startsWith("/")) {
                                continue;
                            }
                            
                            // Extract restaurant names from formatted messages (first line only)
                            // Format: "📍 Restaurant Name\n⭐ rating...\n🍽️ cuisine...\n📍 address..."
                            // We want only the first 📍 line (name), not the address line
                            String[] contentLines = content.split("\n");
                            for (int i = 0; i < contentLines.length; i++) {
                                String line = contentLines[i];
                                if (line.startsWith("📍 ")) {
                                    String text = line.substring(2).trim();
                                    // Only take the FIRST 📍 line (restaurant name)
                                    // Skip subsequent 📍 lines (they're addresses)
                                    if (!restaurantNames.contains(text)) {
                                        restaurantNames.add(text);
                                    }
                                    break; // Stop after first 📍 in this message
                                }
                            }
                        }
                        
                        // Only add if we found actual restaurant recommendations
                        if (!restaurantNames.isEmpty()) {
                            enhanced.append("Previously recommended: ")
                                   .append(String.join(", ", restaurantNames))
                                   .append("\n\n");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error parsing chat history JSON: " + e.getMessage());
            }
        }
        
        // Skip recent conversation if we already have history search results (avoid duplication)
        if (!context.hasHistorySearch() && recentHistory != null && !recentHistory.isEmpty()) {
            // Only include very recent context if no history search was performed
            String[] lines = recentHistory.split("\\\\n");
            List<String> filteredLines = new ArrayList<>();
            
            for (String line : lines) {
                // Skip error messages, help text, and very long lines
                if (!line.contains("Error calling Yelp API") && 
                    !line.contains("VALIDATION_ERROR") &&
                    !line.contains("Bad Request") &&
                    !line.contains("Commands:") &&
                    line.length() < 200) {
                    filteredLines.add(line);
                }
            }
            
            // Keep only the last 2 exchanges
            int linesToKeep = Math.min(4, filteredLines.size());
            List<String> recentLines = filteredLines.subList(
                Math.max(0, filteredLines.size() - linesToKeep), 
                filteredLines.size()
            );
            
            if (!recentLines.isEmpty()) {
                enhanced.append("Recent: ").append(String.join(" | ", recentLines)).append("\n\n");
            }
        }
        
        // Add current query
        // Only add "Current query:" label if we have context to separate it from
        if (enhanced.length() > 0) {
            enhanced.append("Current query: ").append(originalQuery);
        } else {
            // No context - just use the raw query for natural language processing
            enhanced.append(originalQuery);
        }
        
        return enhanced.toString();
    }
    
    /**
     * Formats a response for recall queries by extracting restaurant details from chat history.
     * Returns full restaurant cards that were found in past recommendations.
     */
    private String formatRecallResponse(String historySearchResult, String userQuery) {
        try {
            JsonNode historyResult = objectMapper.readTree(historySearchResult);
            boolean found = historyResult.path("found").asBoolean(false);
            
            if (!found) {
                return null;
            }
            
            JsonNode results = historyResult.path("results");
            if (!results.isArray() || results.size() == 0) {
                return null;
            }
            
            StringBuilder response = new StringBuilder();
            int restaurantCount = 0;
            
            // Extract full restaurant cards (messages with 📍 format)
            for (JsonNode msg : results) {
                String content = msg.path("content").asText();
                
                // Skip help messages, commands, user queries, and recall responses
                if (content.contains("Commands:") || content.startsWith("/") || 
                    content.contains("what were") || content.contains("show me") ||
                    content.startsWith("Here are the")) {
                    continue;
                }
                
                // Check if this is a formatted restaurant card
                if (content.startsWith("📍 ")) {
                    if (restaurantCount > 0) {
                        response.append("\n\n");
                    }
                    response.append(content);
                    restaurantCount++;
                }
            }
            
            if (restaurantCount == 0) {
                return null;
            }
            
            // Add a friendly intro
            String intro = String.format("Here are the sushi places I recommended:\n\n");
            return intro + response.toString();
            
        } catch (Exception e) {
            System.err.println("Error formatting recall response: " + e.getMessage());
            return null;
        }
    }
    
    // ============================================================================
    // Result Classes
    // ============================================================================
    
    public static class YelpResult {
        private final List<String> messages;
        private final List<List<String>> photos;
        private final String yelpConversationId;
        private final List<RestaurantData> restaurants;
        
        public YelpResult(List<String> messages, List<List<String>> photos, String yelpConversationId, List<RestaurantData> restaurants) {
            this.messages = messages;
            this.photos = photos;
            this.yelpConversationId = yelpConversationId;
            this.restaurants = restaurants;
        }
        
        public YelpResult(List<String> messages, List<List<String>> photos, String yelpConversationId) {
            this(messages, photos, yelpConversationId, new ArrayList<>());
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
        
        public List<RestaurantData> getRestaurants() {
            return restaurants;
        }
    }
    
    /**
     * Context result from OpenAI's DB MCP tool gathering.
     * Contains user preferences and/or specific chat history search results.
     */
    private static class ContextResult {
        private boolean hasPreferences = false;
        private String preferencesJson = null;
        private boolean hasHistorySearch = false;
        private String historySearchResult = null;
        private String summary = "";
        
        public boolean hasPreferences() {
            return hasPreferences;
        }
        
        public void setHasPreferences(boolean hasPreferences) {
            this.hasPreferences = hasPreferences;
        }
        
        public String getPreferencesJson() {
            return preferencesJson;
        }
        
        public void setPreferencesJson(String preferencesJson) {
            this.preferencesJson = preferencesJson;
        }
        
        public boolean hasHistorySearch() {
            return hasHistorySearch;
        }
        
        public void setHasHistorySearch(boolean hasHistorySearch) {
            this.hasHistorySearch = hasHistorySearch;
        }
        
        public String getHistorySearchResult() {
            return historySearchResult;
        }
        
        public void setHistorySearchResult(String historySearchResult) {
            this.historySearchResult = historySearchResult;
        }
        
        public String getSummary() {
            return summary;
        }
        
        public void setSummary(String summary) {
            this.summary = summary;
        }
    }
}
