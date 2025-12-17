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
            // Step 0: Check if this is an informational question about previous recommendations
            if (yelpConversationId != null && !yelpConversationId.isEmpty()) {
                String queryType = classifyQueryType(userQuery, chatHistory);
                if ("INFORMATIONAL".equals(queryType)) {
                    // Check if query references past time periods or recall requests
                    boolean isCrossSession = userQuery.toLowerCase().matches(".*(yesterday|last week|last time|previous|earlier|before|ago|other day|recall|remember|gave me).*");
                    
                    if (isCrossSession) {
                        System.out.println("[QUERY TYPE] Cross-session informational query - searching chat history");
                        // Search our database instead of Yelp's session memory
                        String historicalContext = callChatHistoryMCP(lineConversationId, userQuery);
                        
                        if (historicalContext != null && !historicalContext.equals("null")) {
                            // Parse the historical results and extract business data
                            List<RestaurantData> historicalBusinesses = parseHistoricalBusinesses(historicalContext);
                            
                            if (!historicalBusinesses.isEmpty()) {
                                // Add to restaurants list so controller sends as Flex Messages
                                restaurants.addAll(historicalBusinesses);
                                messages.add("Here are the places I previously recommended:");
                                photosList.add(new ArrayList<>());
                            } else {
                                // No business data found, return raw text
                                messages.add("Based on our conversation history:\n\n" + historicalContext);
                                photosList.add(new ArrayList<>());
                            }
                        } else {
                            messages.add("I don't have a record of those recommendations in our recent conversations. Would you like me to search for some now?");
                            photosList.add(new ArrayList<>());
                        }
                        return new YelpResult(messages, photosList, yelpConversationId, restaurants);
                    }
                    
                    System.out.println("[QUERY TYPE] Informational question detected - using Yelp conversational mode");
                    // Let Yelp Fusion AI handle the follow-up question conversationally
                    YelpApiService.YelpChatResult yelpResult = yelpApiService.queryYelpAI(
                        userQuery,
                        yelpConversationId,
                        null,
                        null
                    );
                    
                    if (yelpResult.getChatId() != null) {
                        newYelpConversationId = yelpResult.getChatId();
                    }
                    
                    // Return ONLY the answer text, strip out all business listings and technical sections
                    String textResponse = yelpResult.getFormattedResponse();
                    
                    // Extract only the introduction/answer text before any business listings
                    int firstBusinessIndex = textResponse.indexOf("## Business");
                    if (firstBusinessIndex > 0) {
                        textResponse = textResponse.substring(0, firstBusinessIndex).trim();
                    }
                    
                    // Clean up any remaining technical sections
                    textResponse = textResponse.replaceAll("(?s)^#.*?## Introduction\\s*", "");
                    textResponse = textResponse.replaceAll("(?m)^##\\s*Chat ID.*", "");
                    textResponse = textResponse.replaceAll("(?m)^##\\s*Introduction\\s*", "");
                    textResponse = textResponse.replaceAll("(?m)^\\s*[A-Za-z][A-Za-z0-9_-]{15,}\\s*$", "");
                    textResponse = textResponse.replaceAll("\\n{3,}", "\n\n");
                    textResponse = textResponse.trim();
                    
                    if (!textResponse.isEmpty()) {
                        messages.add(textResponse);
                        photosList.add(new ArrayList<>()); // No photos for informational responses
                    }
                    return new YelpResult(messages, photosList, newYelpConversationId);
                }
            }
            
            // Step 1: Gather context from DB MCPs using OpenAI (user preferences, specific history)
            ContextResult context = gatherContextWithOpenAI(userQuery, lineConversationId, yelpConversationId);
            
            // Step 2: Check if this is a recall query (user asking about PAST recommendations)
            // Only treat as recall if query references past time or if it's explicitly asking "what did you recommend"
            boolean isRecallQuery = userQuery.toLowerCase().matches(".*(yesterday|last week|last time|previous|earlier|before|ago|other day|recall|remember|gave me|what were|show me what|told me about).*");
            
            if (isRecallQuery && context.hasHistorySearch() && context.getHistorySearchResult() != null) {
                // For recall queries, parse and return businesses as Flex Messages
                System.out.println("Answering recall query from chat history (no Yelp call needed)");
                List<RestaurantData> historicalBusinesses = parseHistoricalBusinesses(context.getHistorySearchResult());
                
                if (!historicalBusinesses.isEmpty()) {
                    // Add to restaurants list so controller sends as Flex Messages
                    restaurants.addAll(historicalBusinesses);
                    messages.add("Here are the places I previously recommended:");
                    photosList.add(new ArrayList<>());
                    return new YelpResult(messages, photosList, yelpConversationId, restaurants);
                } else {
                    // No businesses found in history - fall through to make a new search
                    System.out.println("[RECALL] No businesses found in history, will make new Yelp search instead");
                    // Don't return here - let it fall through to normal Yelp search below
                }
            }
            
            // Step 3: Build enhanced query with all context for new search
            String enhancedQuery = buildEnhancedQuery(userQuery, context, chatHistory);
            
            // Step 3.5: Determine if we should use with_reasoning (structured restaurant data)
            // with_reasoning=true: Only for restaurant-specific queries (returns structured data)
            // with_reasoning=false: For general business queries (activities, attractions, shops, etc.)
            boolean isRestaurantQuery = isRestaurantFocusedQuery(userQuery);
            
            // Step 3.6: Rephrase "activity" queries into business/venue queries for Yelp
            // Yelp works better with concrete business types than abstract "activities"
            if (!isRestaurantQuery) {
                enhancedQuery = rephraseActivityQueryForYelp(enhancedQuery);
            }
            
            System.out.println("Enhanced query for Yelp API:\n" + enhancedQuery);
            System.out.println("[QUERY TYPE] Restaurant-focused: " + isRestaurantQuery + " (with_reasoning=" + isRestaurantQuery + ")");
            
            // Step 4: Direct call to Yelp Fusion AI API via Java service
            // Let Yelp ask for location naturally in conversation
            YelpApiService.YelpChatResult yelpResult = yelpApiService.queryYelpAI(
                enhancedQuery,
                yelpConversationId,
                null,  // Let Yelp extract location from query or ask user
                null,  // Let Yelp extract location from query or ask user
                isRestaurantQuery  // Only use with_reasoning for restaurant queries
            );
            
            if (yelpResult.getChatId() != null) {
                newYelpConversationId = yelpResult.getChatId();
            }
            
            // Step 5: Extract structured business data from response
            // YelpResponseFormatter handles both with_reasoning=true and with_reasoning=false
            // It extracts from entities array which is present in both cases
            if (yelpResult.getRawResponse() != null) {
                System.out.println("[EXTRACT] Extracting business data from Yelp response...");
                System.out.println("[EXTRACT] Raw response has 'entities': " + yelpResult.getRawResponse().has("entities"));
                YelpResponseFormatter formatter = new YelpResponseFormatter();
                restaurants = formatter.extractRestaurantData(yelpResult.getRawResponse());
                System.out.println("[EXTRACT] Extracted " + restaurants.size() + " businesses");
                
                if (restaurants.isEmpty()) {
                    System.out.println("[EXTRACT] WARNING: No businesses extracted! Raw response preview: " + 
                        yelpResult.getRawResponse().toString().substring(0, Math.min(500, yelpResult.getRawResponse().toString().length())));
                }
                
                // Step 5.5: Enhance reasoning with preferences (only for restaurant queries)
                if (isRestaurantQuery && !restaurants.isEmpty()) {
                    enhanceReasoningWithPreferences(restaurants, lineConversationId, lineConversationId);
                }
            } else {
                System.out.println("[EXTRACT] No raw response available for extraction");
            }
            
            // Step 6: Clean up and split the formatted response into separate messages with photos
            // Only include text messages if we DON'T have structured restaurant data
            // When we have restaurants, we'll send Flex Messages instead of text
            if (restaurants.isEmpty()) {
                System.out.println("[RESPONSE] No structured restaurants found, using text format");
                ResponseWithPhotos response = cleanupAndSplitYelpResponse(yelpResult.getFormattedResponse());
                messages.addAll(response.messages);
                photosList.addAll(response.photos);
            } else {
                System.out.println("[RESPONSE] Using Flex Message format for " + restaurants.size() + " restaurants");
                // Extract only the introduction text (before restaurant listings) if present
                String formattedResponse = yelpResult.getFormattedResponse();
                int firstBusinessIndex = formattedResponse.indexOf("## Business");
                if (firstBusinessIndex > 0) {
                    String intro = formattedResponse.substring(0, firstBusinessIndex).trim();
                    // Clean up intro
                    intro = intro.replaceAll("(?s)^#.*?## Introduction\\s*", "");
                    intro = intro.replaceAll("(?m)^##\\s*Chat ID.*", "");
                    intro = intro.replaceAll("(?m)^\\s*[A-Za-z][A-Za-z0-9_-]{15,}\\s*$", "");
                    intro = intro.replaceAll("\\n{3,}", "\n\n");
                    intro = intro.trim();
                    
                    if (!intro.isEmpty()) {
                        messages.add(intro);
                        photosList.add(new ArrayList<>());
                    }
                }
            }
            
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
     * Extract business data from Yelp's formatted text response (for general queries without with_reasoning)
     */
    private List<RestaurantData> extractBusinessesFromFormattedResponse(String formattedResponse) {
        List<RestaurantData> businesses = new ArrayList<>();
        
        try {
            // Look for business entries - format is:
            // Business Name
            // ⭐ 4.5/5 (123 reviews) • $$
            // 🎮 Category1, Category2
            // 📍 Address
            String[] lines = formattedResponse.split("\n");
            RestaurantData currentBusiness = null;
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                
                // Check if next line starts with rating (⭐) - if so, current line is business name
                if (i + 1 < lines.length && lines[i + 1].trim().startsWith("⭐")) {
                    // Save previous business if exists
                    if (currentBusiness != null && currentBusiness.getName() != null && !currentBusiness.getName().isEmpty()) {
                        businesses.add(currentBusiness);
                    }
                    
                    // Start new business
                    currentBusiness = new RestaurantData();
                    currentBusiness.setName(line);
                    
                } else if (line.startsWith("⭐ ")) {
                    // Rating and price
                    if (currentBusiness != null) {
                        String[] parts = line.substring(2).split("•");
                        if (parts.length > 0) {
                            try {
                                // Parse rating like "4.5/5" or "4.5"
                                String ratingStr = parts[0].trim().split("/")[0];
                                currentBusiness.setRating(Double.parseDouble(ratingStr));
                            } catch (NumberFormatException e) {
                                // If parsing fails, just skip rating
                            }
                        }
                        if (parts.length > 1) {
                            currentBusiness.setPrice(parts[1].trim());
                        }
                    }
                    
                } else if (line.matches("^[🍽️🎮🎯🎨🏃‍♂️🎭🛍️🏨💊🚗🔧✂️📚] .+")) {
                    // Category/cuisine (can be various emojis: 🍽️ for restaurants, 🎮 for arcades, etc.)
                    if (currentBusiness != null) {
                        // Remove the emoji (first 2 chars including space) and extract category
                        currentBusiness.setCuisine(line.substring(2).trim());
                    }
                    
                } else if (line.startsWith("📍 ")) {
                    // Address
                    if (currentBusiness != null) {
                        currentBusiness.setAddress(line.substring(2).trim());
                    }
                    
                } else if (line.startsWith("📞 ")) {
                    // Phone
                    if (currentBusiness != null) {
                        currentBusiness.setPhone(line.substring(2).trim());
                    }
                    
                } else if (line.startsWith("🔗 ")) {
                    // URL
                    if (currentBusiness != null) {
                        String url = line.substring(2).trim();
                        currentBusiness.setUrl(url);
                        
                        // Extract restaurant ID from URL
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("yelp\\.com/biz/([^?]+)");
                        java.util.regex.Matcher matcher = pattern.matcher(url);
                        if (matcher.find()) {
                            currentBusiness.setRestaurantId(matcher.group(1));
                        }
                    }
                    
                } else if (line.contains("http") && currentBusiness != null && currentBusiness.getImageUrl() == null) {
                    // Image URL
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(https://s3-media[^\\s]+\\.jpg)");
                    java.util.regex.Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        currentBusiness.setImageUrl(matcher.group(1));
                    }
                }
            }
            
            // Add last business
            if (currentBusiness != null && currentBusiness.getName() != null && !currentBusiness.getName().isEmpty()) {
                businesses.add(currentBusiness);
            }
            
            System.out.println("[EXTRACT] Parsed " + businesses.size() + " businesses from formatted response");
            
        } catch (Exception e) {
            System.err.println("[EXTRACT] Error extracting businesses from formatted response: " + e.getMessage());
            e.printStackTrace();
        }
        
        return businesses;
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
    
    /**
     * Classifies if the query is a new recommendation request or informational question
     * about previous recommendations.
     * 
     * @param userQuery The user's query
     * @param chatHistory Recent conversation history
     * @return "INFORMATIONAL" if asking about previous recommendations, "RECOMMENDATION" otherwise
     */
    private String classifyQueryType(String userQuery, String chatHistory) {
        try {
            String systemPrompt = """
                You are a query classifier for a restaurant recommendation bot. Your job is to determine if the user is:
                
                1. INFORMATIONAL: Asking a question about a SPECIFIC previously recommended restaurant
                   Examples: "does it have vegan options?", "what are their hours?", "do they have parking?", 
                   "is the Brew City Grill wheelchair accessible?", "does that place take reservations?"
                   
                2. RECOMMENDATION: Requesting NEW restaurant recommendations OR asking about different places
                   Examples: "find me a restaurant", "where should I eat?", "I want Italian food", 
                   "are there any spots with beer?", "I want to drink today", "show me pizza places",
                   "any good sushi nearby?", "what about Chinese food?"
                
                CRITICAL: If the user is asking about "any spots", "any places", "anywhere", "other options",
                or introducing NEW search criteria (food type, ambiance, activity like "drink", "eat"), 
                it's ALWAYS a RECOMMENDATION request, NOT informational.
                
                Key indicators for INFORMATIONAL (must have ALL of these):
                - Uses pronouns referring to ONE specific restaurant: "it", "they", "their", "the place", "that restaurant"
                - Asks about specific attributes of THAT restaurant: hours, menu items, accessibility, payment methods
                - Does NOT introduce new search criteria or ask about "any" or "other" places
                
                Key indicators for RECOMMENDATION (if ANY of these):
                - Asks "are there", "any spots", "any places", "anywhere", "other options"
                - Introduces NEW criteria: food type, cuisine, drinks, ambiance, location, price
                - Uses action verbs: "find", "recommend", "suggest", "show", "look for", "want"
                - Mentions activities: "eat", "drink", "dine", "grab food"
                
                When in doubt, choose RECOMMENDATION (safer to show options than refuse to help).
                
                Respond with ONLY one word: "INFORMATIONAL" or "RECOMMENDATION"
                """;
            
            String userPrompt = String.format("""
                Chat History (last few messages):
                %s
                
                Current User Query: %s
                
                Classification:""", 
                chatHistory != null && !chatHistory.isEmpty() ? chatHistory : "No previous context",
                userQuery
            );
            
            // Build request JSON
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", openAIProperties.getModel());
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 10);
            
            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
            
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);
            
            requestBody.set("messages", messages);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAIProperties.getApiKey());
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                OPENAI_API_URL,
                HttpMethod.POST,
                entity,
                String.class
            );
            
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("[CLASSIFY] Error: " + response.getStatusCodeValue());
                return "RECOMMENDATION"; // Default to recommendation on error
            }
            
            JsonNode root = objectMapper.readTree(response.getBody());
            String classification = root.path("choices").get(0)
                .path("message").path("content").asText().trim().toUpperCase();
            
            System.out.println("[CLASSIFY] Query type: " + classification);
            return classification.contains("INFORMATIONAL") ? "INFORMATIONAL" : "RECOMMENDATION";
        } catch (Exception e) {
            System.err.println("[CLASSIFY] Error classifying query: " + e.getMessage());
            return "RECOMMENDATION"; // Default to recommendation on error
        }
    }
    
    /**
     * Rephrases "activity" queries into business/venue queries that Yelp can understand.
     * Yelp works better with concrete business types than abstract "activities".
     * 
     * @param query The original query
     * @return Rephrased query focused on businesses/venues
     */
    private String rephraseActivityQueryForYelp(String query) {
        String lowerQuery = query.toLowerCase();
        
        // If query mentions "activities" or "things to do", rephrase it
        if (lowerQuery.contains("activit") || lowerQuery.contains("thing to do") || 
            lowerQuery.contains("date idea") || lowerQuery.contains("fun")) {
            
            // Extract location if present
            String location = "";
            if (lowerQuery.contains(" in ") || lowerQuery.contains(" for ")) {
                // Keep the location context
                location = query.replaceAll("(?i).*?\\b(in|for)\\s+", "");
            }
            
            // Rephrase to focus on businesses and venues
            return String.format(
                "Find fun and interesting venues, entertainment spots, cafes, bars, breweries, dessert shops, " +
                "arcades, bowling alleys, or unique local businesses perfect for a date or outing%s. " +
                "Include a mix of dining and non-dining options.",
                location.isEmpty() ? "" : " in " + location
            );
        }
        
        return query;
    }
    
    /**
     * Determines if a query is specifically about restaurants/dining or general businesses.
     * Restaurant queries should use with_reasoning=true for structured data.
     * General queries (activities, shops, attractions) should use with_reasoning=false.
     * 
     * @param query The user's query
     * @return true if query is restaurant-focused, false for general businesses
     */
    private boolean isRestaurantFocusedQuery(String query) {
        String lowerQuery = query.toLowerCase();
        
        // Keywords that indicate general business/activity queries (NOT restaurant-specific)
        String[] generalKeywords = {
            "activit", "attraction", "thing to do", "date idea", "fun", "entertainment",
            "park", "museum", "shop", "store", "mall", "theater", "cinema", "movie",
            "bowling", "arcade", "mini golf", "hiking", "beach", "trail"
        };
        
        // Keywords that indicate restaurant/dining queries
        String[] restaurantKeywords = {
            "restaurant", "food", "eat", "dining", "lunch", "dinner", "breakfast", "brunch",
            "cafe", "coffee", "bar", "drink", "cuisine", "meal", "hungry", "menu"
        };
        
        // Check if query contains general business keywords
        for (String keyword : generalKeywords) {
            if (lowerQuery.contains(keyword)) {
                // If it also mentions food/dining, it's still restaurant-focused
                for (String restKeyword : restaurantKeywords) {
                    if (lowerQuery.contains(restKeyword)) {
                        return true;
                    }
                }
                // Pure activity/attraction query
                return false;
            }
        }
        
        // Check if query explicitly mentions restaurants/dining
        for (String keyword : restaurantKeywords) {
            if (lowerQuery.contains(keyword)) {
                return true;
            }
        }
        
        // Default to restaurant query for ambiguous cases
        return true;
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
            ProcessBuilder pb = new ProcessBuilder("node", "/app/user-prefs-mcp/dist/index.js");
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
            ProcessBuilder pb = new ProcessBuilder("node", "/app/chat-history-mcp/dist/index.js");
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
            
            // Read JSON-RPC response (with timeout)
            String response = null;
            StringBuilder stderrOutput = new StringBuilder();
            
            // Read stderr in separate thread to capture errors
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        stderrOutput.append(line).append("\n");
                    }
                } catch (IOException e) {
                    System.err.println("[MCP] Error reading stderr: " + e.getMessage());
                }
            });
            stderrThread.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                // Wait up to 5 seconds for response
                long startTime = System.currentTimeMillis();
                while (response == null && (System.currentTimeMillis() - startTime) < 5000) {
                    if (reader.ready()) {
                        response = reader.readLine();
                        break;
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Wait for stderr thread to finish
            stderrThread.join(1000);
            
            if (stderrOutput.length() > 0) {
                System.out.println("[MCP] Stderr output: " + stderrOutput.toString());
            }
            
            System.out.println("[MCP] Received response: " + response);
            
            process.destroy();
            
            if (response != null && !response.trim().isEmpty()) {
                try {
                    JsonNode responseNode = objectMapper.readTree(response);
                    JsonNode result = responseNode.path("result");
                    JsonNode content = result.path("content").get(0);
                    String historyJson = content.path("text").asText();
                    System.out.println("[MCP] Chat history retrieved: " + historyJson);
                    return historyJson;
                } catch (Exception parseEx) {
                    System.err.println("[MCP] Failed to parse MCP response: " + parseEx.getMessage());
                    System.err.println("[MCP] Raw response was: " + response);
                }
            } else {
                System.err.println("[MCP] No response received from MCP process");
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
            ProcessBuilder pb = new ProcessBuilder("node", "/app/conversation-context-mcp/dist/index.js");
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
    
    /**
     * Parse historical business data from chat history MCP response
     */
    private List<RestaurantData> parseHistoricalBusinesses(String historicalContext) {
        List<RestaurantData> businesses = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(historicalContext);
            if (root.has("results") && root.get("results").isArray()) {
                for (JsonNode result : root.get("results")) {
                    if (result.has("content") && result.has("role") && 
                        "assistant".equals(result.get("role").asText())) {
                        String content = result.get("content").asText();
                        
                        // Parse the formatted business text (e.g., "📍 Name\n⭐ Rating...")
                        RestaurantData business = parseBusinessFromText(content);
                        if (business != null) {
                            businesses.add(business);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[HISTORICAL_PARSE] Error parsing historical businesses: " + e.getMessage());
        }
        return businesses;
    }
    
    /**
     * Parse a business from formatted text (e.g., "📍 Name\n⭐ Rating...")
     */
    private RestaurantData parseBusinessFromText(String text) {
        try {
            // Extract name (after 📍)
            String name = extractField(text, "📍 ", "\n");
            if (name == null || name.isEmpty()) {
                return null;
            }
            
            // Extract rating and price (after ⭐)
            String ratingLine = extractField(text, "⭐ ", "\n");
            double rating = 0.0;
            String price = null;
            if (ratingLine != null) {
                String[] parts = ratingLine.split("•");
                if (parts.length > 0) {
                    String ratingStr = parts[0].trim().split("/")[0].trim();
                    try {
                        rating = Double.parseDouble(ratingStr);
                    } catch (NumberFormatException ignored) {}
                }
                if (parts.length > 1) {
                    price = parts[1].trim();
                }
            }
            
            // Extract cuisine (after 🍽️)
            String cuisine = extractField(text, "🍽️ ", "\n");
            
            // Extract address (after second 📍)
            int secondLocation = text.indexOf("📍", text.indexOf("📍") + 1);
            String address = null;
            if (secondLocation >= 0) {
                address = extractField(text.substring(secondLocation), "📍 ", "\n");
            }
            
            // Extract phone (after 📞)
            String phone = extractField(text, "📞 ", "\n");
            
            // Extract URL (after 🔗)
            String url = extractField(text, "🔗 ", "\n");
            if (url != null && url.startsWith("yelp.com/")) {
                url = "https://" + url;
            }
            
            RestaurantData business = new RestaurantData();
            business.setName(name);
            business.setRating(rating);
            business.setPrice(price);
            business.setCuisine(cuisine);
            business.setAddress(address);
            business.setPhone(phone);
            business.setUrl(url);
            
            return business;
        } catch (Exception e) {
            System.err.println("[BUSINESS_PARSE] Error parsing business text: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract a field from text between start and end markers
     */
    private String extractField(String text, String start, String end) {
        int startIdx = text.indexOf(start);
        if (startIdx < 0) {
            return null;
        }
        startIdx += start.length();
        int endIdx = text.indexOf(end, startIdx);
        if (endIdx < 0) {
            endIdx = text.length();
        }
        return text.substring(startIdx, endIdx).trim();
    }
    
    /**
     * Format a business as text for LINE message
     */
    private String formatBusinessAsText(RestaurantData business) {
        StringBuilder sb = new StringBuilder();
        sb.append("📍 ").append(business.getName()).append("\n");
        
        if (business.getRating() > 0) {
            sb.append("⭐ ").append(business.getRating()).append("/5");
            if (business.getPrice() != null && !business.getPrice().isEmpty()) {
                sb.append(" • ").append(business.getPrice());
            }
            sb.append("\n");
        }
        
        if (business.getCuisine() != null && !business.getCuisine().isEmpty()) {
            sb.append("🍽️ ").append(business.getCuisine()).append("\n");
        }
        
        if (business.getAddress() != null && !business.getAddress().isEmpty()) {
            sb.append("📍 ").append(business.getAddress()).append("\n");
        }
        
        if (business.getPhone() != null && !business.getPhone().isEmpty()) {
            sb.append("📞 ").append(business.getPhone()).append("\n");
        }
        
        if (business.getUrl() != null && !business.getUrl().isEmpty()) {
            sb.append("🔗 ").append(business.getUrl());
        }
        
        return sb.toString().trim();
    }
}
