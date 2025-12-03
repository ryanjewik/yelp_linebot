package com.ryanhideo.linebot.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Formats Yelp Fusion AI API responses into readable markdown text.
 * Port of the Python formatters.py logic to Java.
 */
public class YelpResponseFormatter {
    
    public String formatFusionAIResponse(JsonNode response) {
        if (!checkResponseFormat(response)) {
            System.err.println("Invalid response format from Fusion AI.");
            return "Invalid response format from Fusion AI.";
        }
        
        try {
            // Get the response text for introduction
            String introText = response.path("response")
                .path("text")
                .asText("Business information")
                .replace("[[HIGHLIGHT]]", "**")
                .replace("[[ENDHIGHLIGHT]]", "**");
            
            // Chat ID
            String chatId = response.path("chat_id").asText("Unknown Chat ID");
            
            // Initialize the formatted output
            StringBuilder formattedOutput = new StringBuilder();
            formattedOutput.append("# Formatted Business Data for LLM Processing\n\n");
            formattedOutput.append("## Introduction\n");
            formattedOutput.append(introText).append("\n \n");
            
            // Check if entities and businesses exist
            List<JsonNode> businesses = new ArrayList<>();
            JsonNode entities = response.path("entities");
            if (entities.isArray()) {
                for (JsonNode entity : entities) {
                    JsonNode businessesNode = entity.path("businesses");
                    if (businessesNode.isArray()) {
                        businessesNode.forEach(businesses::add);
                        break;
                    }
                }
            }
            
            System.out.println("[FORMATTER] Found " + businesses.size() + " businesses for the query");
            
            // Limit to 3 businesses max
            int maxBusinesses = 3;
            int businessCount = Math.min(businesses.size(), maxBusinesses);
            System.out.println("[FORMATTER] Limiting to " + businessCount + " businesses");
            
            // Process each Business
            for (int index = 0; index < businessCount; index++) {
                JsonNode business = businesses.get(index);
                String name = business.path("name").asText("Unknown");
                formattedOutput.append("\n## Business ").append(index + 1).append(": ").append(name).append("\n");
                
                // Rating and reviews
                double rating = business.path("rating").asDouble(0);
                int reviewCount = business.path("review_count").asInt(0);
                String price = business.path("price").asText("");
                
                if (!price.isEmpty()) {
                    formattedOutput.append("- **Price**: ").append(price).append("\n");
                } else {
                    formattedOutput.append("- **Price**: Not available\n");
                }
                
                if (rating > 0) {
                    String reviewInfo = rating + "/5";
                    if (reviewCount > 0) {
                        reviewInfo += " (" + reviewCount + " reviews)";
                    }
                    formattedOutput.append("- **Rating**: ").append(reviewInfo).append("\n");
                }
                
                // Categories
                JsonNode categories = business.path("categories");
                if (categories.isArray() && categories.size() > 0) {
                    List<String> catTitles = new ArrayList<>();
                    categories.forEach(cat -> {
                        String title = cat.path("title").asText("");
                        if (!title.isEmpty()) catTitles.add(title);
                    });
                    if (!catTitles.isEmpty()) {
                        formattedOutput.append("- **Type**: ").append(String.join(", ", catTitles)).append("\n");
                    }
                }
                
                // Location
                JsonNode location = business.path("location");
                if (!location.isMissingNode()) {
                    String formattedAddress = location.path("formatted_address").asText("");
                    if (!formattedAddress.isEmpty()) {
                        String address = formattedAddress.replace("\n", ", ");
                        formattedOutput.append("- **Location**: ").append(address).append("\n");
                    }
                }
                
                // Coordinates
                JsonNode coordinates = business.path("coordinates");
                if (!coordinates.isMissingNode()) {
                    double lat = coordinates.path("latitude").asDouble(0);
                    double lon = coordinates.path("longitude").asDouble(0);
                    if (lat != 0 && lon != 0) {
                        formattedOutput.append("- **Coordinates**: ").append(lat).append(", ").append(lon).append("\n");
                    } else {
                        formattedOutput.append("- **Coordinates**: Not available\n");
                    }
                } else {
                    formattedOutput.append("- **Coordinates**: Not available\n");
                }
                
                // URL
                String url = business.path("url").asText("");
                if (!url.isEmpty()) {
                    formattedOutput.append("- **URL**: [View on Yelp](").append(url).append(")\n");
                }
                
                // Phone
                String phone = business.path("phone").asText("");
                if (!phone.isEmpty()) {
                    formattedOutput.append("- **Phone**: ").append(phone).append("\n");
                }
                
                // Website
                String website = business.path("attributes").path("BusinessUrl").asText("");
                if (!website.isEmpty()) {
                    formattedOutput.append("- **Website**: ").append(website).append("\n");
                }
                
                // Services
                JsonNode attributes = business.path("attributes");
                if (!attributes.isMissingNode()) {
                    String formattedAttributes = formatBusinessAttributes(attributes);
                    if (!formattedAttributes.isEmpty()) {
                        formattedOutput.append("- **Services and Amenities**: \n  - ")
                            .append(formattedAttributes).append("\n");
                    }
                }
                
                // Contextual Info
                JsonNode contextualInfo = business.path("contextual_info");
                
                // Business hours
                JsonNode businessHours = contextualInfo.path("business_hours");
                if (businessHours.isArray() && businessHours.size() > 0) {
                    formattedOutput.append("- **Hours**:\n");
                    for (JsonNode day : businessHours) {
                        String dayName = day.path("day_of_week").asText("Unknown");
                        JsonNode hours = day.path("business_hours");
                        if (hours.isArray() && hours.size() > 0) {
                            String openTime = hours.get(0).path("open_time").asText("");
                            String closeTime = hours.get(0).path("close_time").asText("");
                            try {
                                LocalDateTime openDt = LocalDateTime.parse(openTime, DateTimeFormatter.ISO_DATE_TIME);
                                LocalDateTime closeDt = LocalDateTime.parse(closeTime, DateTimeFormatter.ISO_DATE_TIME);
                                String openStr = openDt.format(DateTimeFormatter.ofPattern("hh:mm a"));
                                String closeStr = closeDt.format(DateTimeFormatter.ofPattern("hh:mm a"));
                                formattedOutput.append("  - ").append(dayName).append(": ")
                                    .append(openStr).append(" - ").append(closeStr).append("\n");
                            } catch (DateTimeParseException e) {
                                formattedOutput.append("  - ").append(dayName).append(": Available\n");
                            }
                        }
                    }
                }
                
                // Overall Review Snippet
                String overallReviewSnippet = contextualInfo.path("review_snippet").asText("");
                if (!overallReviewSnippet.isEmpty()) {
                    String reviewText = overallReviewSnippet
                        .replace("[[HIGHLIGHT]]", "**")
                        .replace("[[ENDHIGHLIGHT]]", "**");
                    formattedOutput.append("- **Review Highlight**: ").append(reviewText).append("\n");
                }
                
                // Individual Review Snippets
                JsonNode reviewSnippets = contextualInfo.path("review_snippets");
                if (reviewSnippets.isArray() && reviewSnippets.size() > 0) {
                    formattedOutput.append("- **Customer Reviews**:\n");
                    for (JsonNode snippet : reviewSnippets) {
                        double snippetRating = snippet.path("rating").asDouble(0);
                        String comment = snippet.path("comment").asText("No comment.")
                            .replace("[[HIGHLIGHT]]", "**")
                            .replace("[[ENDHIGHLIGHT]]", "**");
                        if (snippetRating > 0) {
                            formattedOutput.append("  - Rating: ").append(snippetRating).append("/5\n");
                            formattedOutput.append("    ").append(comment).append("\n");
                        } else {
                            formattedOutput.append("  - ").append(comment).append("\n");
                        }
                    }
                }
                
                // Photos
                JsonNode photos = contextualInfo.path("photos");
                if (photos.isArray() && photos.size() > 0) {
                    formattedOutput.append("- **Photos**:\n");
                    for (JsonNode photo : photos) {
                        String photoUrl = photo.path("original_url").asText("");
                        if (!photoUrl.isEmpty()) {
                            formattedOutput.append("  - ").append(photoUrl).append("\n");
                        }
                    }
                }
                
                // Description from summaries
                JsonNode summaries = business.path("summaries");
                String longSummary = summaries.path("long").asText("");
                String shortSummary = summaries.path("short").asText("");
                String description = !longSummary.isEmpty() ? longSummary : shortSummary;
                if (!description.isEmpty()) {
                    formattedOutput.append("- **Description**: ").append(description).append("\n");
                }
            }
            
            String result = formattedOutput.toString();
            System.out.println("[FORMATTER] Formatted output for LLM");
            return result;
            
        } catch (Exception e) {
            System.err.println("[FORMATTER] Error: " + e.getMessage());
            e.printStackTrace();
            return "Unable to fetch data from Yelp. Invalid response format.";
        }
    }
    
    private boolean checkResponseFormat(JsonNode response) {
        return response != null
            && response.has("response")
            && response.get("response").has("text")
            && response.has("entities")
            && response.has("chat_id");
    }
    
    private String formatBusinessAttributes(JsonNode attributes) {
        if (attributes == null || attributes.isMissingNode()) {
            return "";
        }
        
        List<String> formattedAttributes = new ArrayList<>();
        
        // Boolean checks with user-friendly names
        checkBooleanAttribute(attributes, "BusinessAcceptsAndroidPay", "Accepts Android Pay ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "BusinessAcceptsApplePay", "Accepts Apple Pay ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "GenderNeutralRestrooms", "Gender-Neutral Restrooms ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "BusinessOpenToAll", "Open to All ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "PokestopNearby", "Pokestop Nearby ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "BikeParking", "Bike Parking Available ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "BusinessAcceptsBitcoin", "Accepts Bitcoin ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "BusinessAcceptsCreditCards", "Accepts Credit Cards ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "Caters", "Catering Available ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "Corkage", "Corkage Available ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "DogsAllowed", "Dog-friendly ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "DriveThru", "Drive-Thru Available ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "FlowerDelivery", "Flower Delivery Available ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "GoodForKids", "Good for Kids ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "HappyHour", "Happy Hour Specials ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "HasTV", "Has TV ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "OffersMilitaryDiscount", "Offers Military Discount ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "OnlineReservations", "Online Reservations ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "Open24Hours", "Open 24 Hours ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "PlatformDelivery", "Platform Delivery ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "RestaurantsCounterService", "Counter Service ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "RestaurantsDelivery", "Offers Delivery ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "RestaurantsGoodForGroups", "Good for Groups ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "RestaurantsReservations", "Takes Reservations ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "RestaurantsTableService", "Table Service ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "RestaurantsTakeOut", "Offers Takeout ✓", formattedAttributes);
        checkBooleanAttribute(attributes, "WheelchairAccessible", "Wheelchair Accessible ✓", formattedAttributes);
        
        // String value attributes
        String alcohol = attributes.path("Alcohol").asText("");
        if (!alcohol.isEmpty() && !alcohol.equals("none")) {
            String alcStr = alcohol.replace("_", " ");
            alcStr = alcStr.substring(0, 1).toUpperCase() + alcStr.substring(1);
            formattedAttributes.add("Alcohol: " + alcStr);
        }
        
        String noise = attributes.path("NoiseLevel").asText("");
        if (!noise.isEmpty()) {
            String noiseStr = noise.replace("_", " ");
            noiseStr = noiseStr.substring(0, 1).toUpperCase() + noiseStr.substring(1);
            formattedAttributes.add("Noise Level: " + noiseStr);
        }
        
        String attire = attributes.path("RestaurantsAttire").asText("");
        if (!attire.isEmpty()) {
            String attireStr = attire.substring(0, 1).toUpperCase() + attire.substring(1);
            formattedAttributes.add("Attire: " + attireStr);
        }
        
        String wifi = attributes.path("WiFi").asText("");
        if (!wifi.isEmpty()) {
            if (wifi.equals("no")) {
                formattedAttributes.add("WiFi: Not Available");
            } else {
                String wifiStr = wifi.substring(0, 1).toUpperCase() + wifi.substring(1);
                formattedAttributes.add("WiFi: " + wifiStr);
            }
        }
        
        // Nested Ambience
        JsonNode ambience = attributes.path("Ambience");
        if (ambience.isObject()) {
            List<String> activeAmbience = new ArrayList<>();
            Iterator<String> fieldNames = ambience.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                if (ambience.get(key).asBoolean(false)) {
                    activeAmbience.add(key.substring(0, 1).toUpperCase() + key.substring(1));
                }
            }
            if (!activeAmbience.isEmpty()) {
                formattedAttributes.add("Ambience: " + String.join(", ", activeAmbience));
            }
        }
        
        // Nested BusinessParking
        JsonNode parking = attributes.path("BusinessParking");
        if (parking.isObject()) {
            List<String> availableParking = new ArrayList<>();
            Iterator<String> fieldNames = parking.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                if (parking.get(key).asBoolean(false)) {
                    availableParking.add(key.substring(0, 1).toUpperCase() + key.substring(1));
                }
            }
            if (!availableParking.isEmpty()) {
                formattedAttributes.add("Parking: " + String.join(", ", availableParking));
            } else {
                formattedAttributes.add("Parking: Not specified");
            }
        }
        
        // BYOB/Corkage
        checkBooleanAttribute(attributes, "BYOB", "BYOB ✓", formattedAttributes);
        String byobCorkage = attributes.path("BYOBCorkage").asText("");
        if (!byobCorkage.isEmpty()) {
            if (byobCorkage.equals("yes_corkage")) {
                formattedAttributes.add("Corkage for BYOB: Yes");
            } else if (byobCorkage.equals("yes_free")) {
                formattedAttributes.add("Corkage for BYOB: Free");
            } else if (!byobCorkage.equals("no")) {
                formattedAttributes.add("BYOB Corkage: " + byobCorkage);
            }
        }
        
        // GoodForMeal
        JsonNode goodForMeal = attributes.path("GoodForMeal");
        if (goodForMeal.isObject()) {
            List<String> suitableMeals = new ArrayList<>();
            Iterator<String> fieldNames = goodForMeal.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                if (goodForMeal.get(key).asBoolean(false)) {
                    suitableMeals.add(key.substring(0, 1).toUpperCase() + key.substring(1));
                }
            }
            if (!suitableMeals.isEmpty()) {
                formattedAttributes.add("Good for: " + String.join(", ", suitableMeals));
            }
        }
        
        // Price Range
        int priceRange = attributes.path("RestaurantsPriceRange2").asInt(0);
        if (priceRange > 0) {
            formattedAttributes.add("Price Range: " + "$".repeat(priceRange));
        }
        
        // About This Biz
        String history = attributes.path("AboutThisBizHistory").asText("");
        if (!history.isEmpty()) {
            String histText = history.length() > 150 ? history.substring(0, 150) + "..." : history;
            formattedAttributes.add("History: " + histText);
        }
        
        String specialties = attributes.path("AboutThisBizSpecialties").asText("");
        if (!specialties.isEmpty()) {
            formattedAttributes.add("Specialties: " + specialties);
        }
        
        String yearEstablished = attributes.path("AboutThisBizYearEstablished").asText("");
        if (!yearEstablished.isEmpty()) {
            formattedAttributes.add("Established: " + yearEstablished);
        }
        
        // Menu URL
        String menuUrl = attributes.path("MenuUrl").asText("");
        if (!menuUrl.isEmpty() && menuUrl.startsWith("http")) {
            formattedAttributes.add("Menu: [View Menu](" + menuUrl + ")");
        }
        
        return String.join("\n  - ", formattedAttributes);
    }
    
    private void checkBooleanAttribute(JsonNode attributes, String key, String text, List<String> formattedAttributes) {
        if (attributes.path(key).asBoolean(false)) {
            formattedAttributes.add(text);
        }
    }
}
