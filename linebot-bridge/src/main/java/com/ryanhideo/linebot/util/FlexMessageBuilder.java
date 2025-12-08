package com.ryanhideo.linebot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class FlexMessageBuilder {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * Build a Flex Message for a restaurant with Like/Dislike buttons
     * 
     * @param restaurantId Unique Yelp business ID
     * @param name Restaurant name
     * @param rating Rating (e.g., "4.5/5")
     * @param price Price level (e.g., "$$")
     * @param cuisine Cuisine type (e.g., "Italian")
     * @param address Restaurant address
     * @param phone Phone number
     * @param url Yelp URL
     * @param imageUrl Restaurant image URL
     * @param reasoning AI reasoning for recommendation
     * @return JSON string for LINE Flex Message
     */
    public static String buildRestaurantFlexMessage(
            String restaurantId,
            String name,
            String rating,
            String price,
            String cuisine,
            String address,
            String phone,
            String url,
            String imageUrl,
            String reasoning
    ) {
        ObjectNode flexMessage = mapper.createObjectNode();
        flexMessage.put("type", "flex");
        flexMessage.put("altText", name + " - Restaurant Recommendation");
        
        ObjectNode contents = mapper.createObjectNode();
        contents.put("type", "bubble");
        
        // Hero image
        if (imageUrl != null && !imageUrl.isEmpty()) {
            ObjectNode hero = mapper.createObjectNode();
            hero.put("type", "image");
            hero.put("url", imageUrl);
            hero.put("size", "full");
            hero.put("aspectRatio", "20:13");
            hero.put("aspectMode", "cover");
            contents.set("hero", hero);
        }
        
        // Body section
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "box");
        body.put("layout", "vertical");
        ArrayNode bodyContents = mapper.createArrayNode();
        
        // Restaurant name
        ObjectNode nameBox = mapper.createObjectNode();
        nameBox.put("type", "text");
        nameBox.put("text", name);
        nameBox.put("weight", "bold");
        nameBox.put("size", "xl");
        nameBox.put("wrap", true);
        bodyContents.add(nameBox);
        
        // Rating and price
        ObjectNode infoBox = mapper.createObjectNode();
        infoBox.put("type", "box");
        infoBox.put("layout", "baseline");
        infoBox.put("margin", "md");
        ArrayNode infoContents = mapper.createArrayNode();
        
        ObjectNode ratingText = mapper.createObjectNode();
        ratingText.put("type", "text");
        ratingText.put("text", "⭐ " + rating);
        ratingText.put("size", "sm");
        ratingText.put("color", "#999999");
        ratingText.put("flex", 0);
        infoContents.add(ratingText);
        
        if (price != null && !price.isEmpty()) {
            ObjectNode priceText = mapper.createObjectNode();
            priceText.put("type", "text");
            priceText.put("text", " • " + price);
            priceText.put("size", "sm");
            priceText.put("color", "#999999");
            priceText.put("flex", 0);
            infoContents.add(priceText);
        }
        
        infoBox.set("contents", infoContents);
        bodyContents.add(infoBox);
        
        // Cuisine
        if (cuisine != null && !cuisine.isEmpty()) {
            ObjectNode cuisineText = mapper.createObjectNode();
            cuisineText.put("type", "text");
            cuisineText.put("text", "🍽️ " + cuisine);
            cuisineText.put("size", "sm");
            cuisineText.put("color", "#666666");
            cuisineText.put("margin", "md");
            bodyContents.add(cuisineText);
        }
        
        // Address
        if (address != null && !address.isEmpty()) {
            ObjectNode addressText = mapper.createObjectNode();
            addressText.put("type", "text");
            addressText.put("text", "📍 " + address);
            addressText.put("size", "xs");
            addressText.put("color", "#999999");
            addressText.put("margin", "md");
            addressText.put("wrap", true);
            bodyContents.add(addressText);
        }
        
        // Phone
        if (phone != null && !phone.isEmpty()) {
            ObjectNode phoneText = mapper.createObjectNode();
            phoneText.put("type", "text");
            phoneText.put("text", "📞 " + phone);
            phoneText.put("size", "xs");
            phoneText.put("color", "#999999");
            phoneText.put("margin", "sm");
            bodyContents.add(phoneText);
        }
        
        // Reasoning/Explanation
        if (reasoning != null && !reasoning.isEmpty()) {
            ObjectNode separator = mapper.createObjectNode();
            separator.put("type", "separator");
            separator.put("margin", "lg");
            bodyContents.add(separator);
            
            ObjectNode reasoningHeader = mapper.createObjectNode();
            reasoningHeader.put("type", "text");
            reasoningHeader.put("text", "💡 Why this recommendation:");
            reasoningHeader.put("weight", "bold");
            reasoningHeader.put("size", "sm");
            reasoningHeader.put("margin", "lg");
            bodyContents.add(reasoningHeader);
            
            ObjectNode reasoningText = mapper.createObjectNode();
            reasoningText.put("type", "text");
            reasoningText.put("text", reasoning);
            reasoningText.put("size", "xs");
            reasoningText.put("color", "#666666");
            reasoningText.put("margin", "sm");
            reasoningText.put("wrap", true);
            bodyContents.add(reasoningText);
        }
        
        body.set("contents", bodyContents);
        contents.set("body", body);
        
        // Footer with buttons
        ObjectNode footer = mapper.createObjectNode();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        ArrayNode footerContents = mapper.createArrayNode();
        
        // Yelp link button
        ObjectNode yelpButton = mapper.createObjectNode();
        yelpButton.put("type", "button");
        yelpButton.put("style", "link");
        yelpButton.put("height", "sm");
        ObjectNode yelpAction = mapper.createObjectNode();
        yelpAction.put("type", "uri");
        yelpAction.put("label", "🔗 View on Yelp");
        yelpAction.put("uri", url);
        yelpButton.set("action", yelpAction);
        footerContents.add(yelpButton);
        
        // Like/Dislike buttons row
        ObjectNode buttonBox = mapper.createObjectNode();
        buttonBox.put("type", "box");
        buttonBox.put("layout", "horizontal");
        buttonBox.put("spacing", "sm");
        ArrayNode buttonContents = mapper.createArrayNode();
        
        // Like button
        ObjectNode likeButton = mapper.createObjectNode();
        likeButton.put("type", "button");
        likeButton.put("style", "primary");
        likeButton.put("color", "#06c755");
        likeButton.put("height", "sm");
        likeButton.put("flex", 1);
        ObjectNode likeAction = mapper.createObjectNode();
        likeAction.put("type", "postback");
        likeAction.put("label", "👍 Like");
        likeAction.put("data", String.format("action=like&restaurantId=%s&name=%s&cuisine=%s&price=%s", 
            restaurantId, urlEncode(name), urlEncode(cuisine), urlEncode(price)));
        likeAction.put("displayText", "👍 Liked " + name);
        likeButton.set("action", likeAction);
        buttonContents.add(likeButton);
        
        // Dislike button
        ObjectNode dislikeButton = mapper.createObjectNode();
        dislikeButton.put("type", "button");
        dislikeButton.put("style", "primary");
        dislikeButton.put("color", "#d32323"); // Yelp red
        dislikeButton.put("height", "sm");
        dislikeButton.put("flex", 1);
        ObjectNode dislikeAction = mapper.createObjectNode();
        dislikeAction.put("type", "postback");
        dislikeAction.put("label", "👎 Dislike");
        dislikeAction.put("data", String.format("action=dislike&restaurantId=%s&name=%s&cuisine=%s&price=%s", 
            restaurantId, urlEncode(name), urlEncode(cuisine), urlEncode(price)));
        dislikeAction.put("displayText", "👎 Disliked " + name);
        dislikeButton.set("action", dislikeAction);
        buttonContents.add(dislikeButton);
        
        buttonBox.set("contents", buttonContents);
        footerContents.add(buttonBox);
        
        footer.set("contents", footerContents);
        contents.set("footer", footer);
        
        flexMessage.set("contents", contents);
        
        try {
            return mapper.writeValueAsString(flexMessage);
        } catch (Exception e) {
            System.err.println("[FLEX] Error building flex message: " + e.getMessage());
            return null;
        }
    }
    
    private static String urlEncode(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
