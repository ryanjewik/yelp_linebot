package com.ryanhideo.linebot.service;

import com.ryanhideo.linebot.model.RestaurantData;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class LineMessageService {
    
    public static class MessageResult {
        private final List<String> replies;
        private final List<List<String>> photos;
        private final String yelpConversationId;
        private final List<RestaurantData> restaurants;
        
        public MessageResult(List<String> replies, List<List<String>> photos, String yelpConversationId, List<RestaurantData> restaurants) {
            this.replies = replies;
            this.photos = photos;
            this.yelpConversationId = yelpConversationId;
            this.restaurants = restaurants;
        }
        
        public MessageResult(List<String> replies, List<List<String>> photos, String yelpConversationId) {
            this(replies, photos, yelpConversationId, new ArrayList<>());
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
        
        public List<RestaurantData> getRestaurants() {
            return restaurants;
        }
    }

    private final YelpService yelpService;
    private final MessageInsertService messageInsertService;
    private final UserPreferencesService userPreferencesService;

    public LineMessageService(YelpService yelpService, MessageInsertService messageInsertService, UserPreferencesService userPreferencesService) {
        this.yelpService = yelpService;
        this.messageInsertService = messageInsertService;
        this.userPreferencesService = userPreferencesService;
    }

    public MessageResult handleTextMessage(String rawText, String messageId, String lineConversationI, String userId, String msgType, String replyId) throws Exception {
        List<String> replies = new ArrayList<>();
        System.out.println("Received text message: " + rawText);
        String trimmed = rawText.trim();
        String lower = trimmed.toLowerCase();
        String yelpConversationId = null;
        boolean yelpCall = false;
        
        // /diet command
        if (trimmed.startsWith("/diet ")) {
            String dietText = trimmed.substring("/diet".length()).trim();
            if (dietText.isEmpty()) {
                replies.add("Usage: /diet <restrictions>\nExample: /diet vegan, gluten-free\n/diet clear - clear only diet restrictions");
            } else if (dietText.equalsIgnoreCase("clear")) {
                userPreferencesService.clearDiet(userId);
                replies.add("✅ Cleared your dietary restrictions");
            } else {
                String[] dietItems = Arrays.stream(dietText.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
                userPreferencesService.appendDiet(userId, dietItems);
                
                // Get updated preferences to show current state
                UserPreferencesService.UserPreferences prefs = userPreferencesService.getUserPreferences(userId);
                replies.add("✅ Added to your dietary restrictions: " + String.join(", ", dietItems) + 
                           "\n\nCurrent diet list: " + String.join(", ", prefs.getDiet()));
            }
            List<List<String>> emptyPhotos = new ArrayList<>();
            emptyPhotos.add(new ArrayList<>());
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        }
        
        // /allergies command
        if (trimmed.startsWith("/allergies ")) {
            String allergiesText = trimmed.substring("/allergies".length()).trim();
            if (allergiesText.isEmpty()) {
                replies.add("Usage: /allergies <allergens>\nExample: /allergies peanuts, shellfish\n/allergies clear - clear only allergies");
            } else if (allergiesText.equalsIgnoreCase("clear")) {
                userPreferencesService.clearAllergies(userId);
                replies.add("✅ Cleared your allergies");
            } else {
                String[] allergyItems = Arrays.stream(allergiesText.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
                userPreferencesService.appendAllergies(userId, allergyItems);
                
                // Get updated preferences to show current state
                UserPreferencesService.UserPreferences prefs = userPreferencesService.getUserPreferences(userId);
                replies.add("✅ Added to your allergies: " + String.join(", ", allergyItems) + 
                           "\n\nCurrent allergies list: " + String.join(", ", prefs.getAllergies()));
            }
            List<List<String>> emptyPhotos = new ArrayList<>();
            emptyPhotos.add(new ArrayList<>());
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        }
        
        // /price command
        if (trimmed.startsWith("/price ")) {
            String priceText = trimmed.substring("/price".length()).trim();
            if (priceText.equalsIgnoreCase("clear")) {
                userPreferencesService.clearPriceRange(userId);
                replies.add("✅ Cleared your price preference");
            } else {
                try {
                    int priceLevel = Integer.parseInt(priceText);
                    if (priceLevel < 1 || priceLevel > 4) {
                        replies.add("❌ Price level must be between 1 and 4\n$ = Budget\n$$ = Moderate\n$$$ = Upscale\n$$$$ = Luxury");
                    } else {
                        userPreferencesService.updatePriceRange(userId, priceLevel);
                        String priceDisplay = "$".repeat(priceLevel);
                        replies.add("✅ Updated your price preference to: " + priceDisplay + " (" + priceLevel + ")");
                    }
                } catch (NumberFormatException e) {
                    replies.add("❌ Invalid price level. Please use a number between 1 and 4\nExample: /price 2\n/price clear - clear only price preference");
                }
            }
            List<List<String>> emptyPhotos = new ArrayList<>();
            emptyPhotos.add(new ArrayList<>());
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        }
        
        // /favorites command
        if (trimmed.startsWith("/favorites ")) {
            String favoritesText = trimmed.substring("/favorites".length()).trim();
            if (favoritesText.isEmpty()) {
                replies.add("Usage: /favorites <cuisines, foods>\nExample: /favorites sushi, Italian, tacos\n/favorites clear - clear only favorite cuisines");
            } else if (favoritesText.equalsIgnoreCase("clear")) {
                userPreferencesService.clearFavoriteCuisines(userId);
                replies.add("✅ Cleared your favorite cuisines");
            } else {
                String[] cuisineItems = Arrays.stream(favoritesText.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
                userPreferencesService.appendFavoriteCuisines(userId, cuisineItems);
                
                // Get updated preferences to show current state
                UserPreferencesService.UserPreferences prefs = userPreferencesService.getUserPreferences(userId);
                replies.add("✅ Added to your favorite cuisines: " + String.join(", ", cuisineItems) + 
                           "\n\nCurrent favorites list: " + String.join(", ", prefs.getFavoriteCuisines()));
            }
            List<List<String>> emptyPhotos = new ArrayList<>();
            emptyPhotos.add(new ArrayList<>());
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        }
        
        // /preferences or /prefs command to view current settings
        if (lower.equals("/preferences") || lower.equals("/prefs") || lower.startsWith("/prefs ") || lower.startsWith("/preferences ")) {
            if (lower.endsWith(" clear") || lower.equals("/prefs clear") || lower.equals("/preferences clear")) {
                userPreferencesService.clearAllPreferences(userId);
                replies.add("✅ Cleared all your preferences (diet, allergies, price, favorites)");
            } else {
                UserPreferencesService.UserPreferences prefs = userPreferencesService.getUserPreferences(userId);
                replies.add(prefs.toDisplayString());
            }
            List<List<String>> emptyPhotos = new ArrayList<>();
            emptyPhotos.add(new ArrayList<>());
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        }
        
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
                YelpService.YelpChatResult result = yelpService.callYelpChat(prompt, lineConversationI, userId);
                replies.addAll(result.getMessages());
                yelpConversationId = result.getChatId();
                messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
                return new MessageResult(result.getMessages(), result.getPhotos(), yelpConversationId, result.getRestaurants());
            }
        }
    
        // help / ping / echo
        List<List<String>> emptyPhotos = new ArrayList<>();
        emptyPhotos.add(new ArrayList<>());
        
        if (lower.equals("/help")) {
            replies.add(
                    "Commands:\n" +
                    "- /help: show this help\n" +
                    "- /diet <restrictions>: add dietary restrictions (e.g. /diet vegan, gluten-free)\n" +
                    "- /allergies <allergens>: add allergens (e.g. /allergies peanuts, shellfish)\n" +
                    "- /price <level>: set price level 1-4 (e.g. /price 2)\n" +
                    "- /favorites <cuisines>: add favorite cuisines (e.g. /favorites sushi, Italian)\n" +
                    "- /prefs: view your current preferences\n" +
                    "- /prefs clear: clear ALL preferences\n" +
                    "- /diet clear, /allergies clear, /price clear, /favorites clear: clear individual preferences\n" +
                    "- /yelp <query>: ask Yelp AI (e.g. /yelp good vegan sushi in SF)"
            );
            // Insert message into database before returning
            messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);
            return new MessageResult(replies, emptyPhotos, yelpConversationId);
        }

        // Insert message into database
        messageInsertService.insertMessage(rawText, yelpCall, messageId, lineConversationI, userId, msgType, replyId, yelpConversationId);

        return new MessageResult(replies, emptyPhotos, yelpConversationId);
    }
}
