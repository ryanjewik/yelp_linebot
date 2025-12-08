package com.ryanhideo.linebot.model;

import java.util.List;

/**
 * Data model for structured restaurant information.
 * Used to build LINE Flex Messages with complete restaurant details.
 */
public class RestaurantData {
    private String restaurantId;
    private String name;
    private double rating;
    private String price;
    private String cuisine;
    private String address;
    private String phone;
    private String url;
    private String imageUrl;
    private String reasoning;
    private List<String> additionalPhotos;
    
    public RestaurantData() {}
    
    public RestaurantData(String restaurantId, String name, double rating, String price, 
                         String cuisine, String address, String phone, String url, 
                         String imageUrl, String reasoning, List<String> additionalPhotos) {
        this.restaurantId = restaurantId;
        this.name = name;
        this.rating = rating;
        this.price = price;
        this.cuisine = cuisine;
        this.address = address;
        this.phone = phone;
        this.url = url;
        this.imageUrl = imageUrl;
        this.reasoning = reasoning;
        this.additionalPhotos = additionalPhotos;
    }
    
    // Getters and setters
    public String getRestaurantId() { return restaurantId; }
    public void setRestaurantId(String restaurantId) { this.restaurantId = restaurantId; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }
    
    public String getPrice() { return price; }
    public void setPrice(String price) { this.price = price; }
    
    public String getCuisine() { return cuisine; }
    public void setCuisine(String cuisine) { this.cuisine = cuisine; }
    
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    
    public List<String> getAdditionalPhotos() { return additionalPhotos; }
    public void setAdditionalPhotos(List<String> additionalPhotos) { this.additionalPhotos = additionalPhotos; }
    
    /**
     * Get price level as integer (1-4) for Neo4j storage.
     */
    public int getPriceLevel() {
        if (price == null || price.isEmpty()) return 0;
        return price.length(); // $ = 1, $$ = 2, $$$ = 3, $$$$ = 4
    }
}
