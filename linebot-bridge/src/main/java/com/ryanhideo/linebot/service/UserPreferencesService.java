package com.ryanhideo.linebot.service;

import com.ryanhideo.linebot.config.PostgresProperties;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.Arrays;

@Service
public class UserPreferencesService {
    
    private final PostgresProperties postgresProps;

    public UserPreferencesService(PostgresProperties postgresProps) {
        this.postgresProps = postgresProps;
    }

    private Connection getConnection() throws SQLException {
        String url = String.format("jdbc:postgresql://%s:%d/%s",
                postgresProps.getHost(),
                postgresProps.getPort(),
                postgresProps.getDbName());
        return DriverManager.getConnection(url, postgresProps.getUser(), postgresProps.getPassword());
    }

    public void updateDiet(String userId, String[] dietItems) {
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO users (userid, diet) VALUES (?, ?) " +
                        "ON CONFLICT (userid) DO UPDATE SET diet = EXCLUDED.diet";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                Array sqlArray = conn.createArrayOf("text", dietItems);
                pstmt.setString(1, userId);
                pstmt.setArray(2, sqlArray);
                pstmt.executeUpdate();
                System.out.println("Updated diet for user " + userId + ": " + Arrays.toString(dietItems));
            }
        } catch (Exception e) {
            System.err.println("Error updating diet: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void updateAllergies(String userId, String[] allergyItems) {
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO users (userid, allergies) VALUES (?, ?) " +
                        "ON CONFLICT (userid) DO UPDATE SET allergies = EXCLUDED.allergies";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                Array sqlArray = conn.createArrayOf("text", allergyItems);
                pstmt.setString(1, userId);
                pstmt.setArray(2, sqlArray);
                pstmt.executeUpdate();
                System.out.println("Updated allergies for user " + userId + ": " + Arrays.toString(allergyItems));
            }
        } catch (Exception e) {
            System.err.println("Error updating allergies: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void updatePriceRange(String userId, int priceLevel) {
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO users (userid, pricerangepref) VALUES (?, ?) " +
                        "ON CONFLICT (userid) DO UPDATE SET pricerangepref = EXCLUDED.pricerangepref";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setInt(2, priceLevel);
                pstmt.executeUpdate();
                System.out.println("Updated price range for user " + userId + ": " + priceLevel);
            }
        } catch (Exception e) {
            System.err.println("Error updating price range: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void updateFavoriteCuisines(String userId, String[] cuisines) {
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO users (userid, favoritecuisines) VALUES (?, ?) " +
                        "ON CONFLICT (userid) DO UPDATE SET favoritecuisines = EXCLUDED.favoritecuisines";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                Array sqlArray = conn.createArrayOf("text", cuisines);
                pstmt.setString(1, userId);
                pstmt.setArray(2, sqlArray);
                pstmt.executeUpdate();
                System.out.println("Updated favorite cuisines for user " + userId + ": " + Arrays.toString(cuisines));
            }
        } catch (Exception e) {
            System.err.println("Error updating favorite cuisines: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public UserPreferences getUserPreferences(String userId) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT favoritecuisines, allergies, pricerangepref, diet FROM users WHERE userid = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        Array cuisinesArray = rs.getArray("favoritecuisines");
                        Array allergiesArray = rs.getArray("allergies");
                        Array dietArray = rs.getArray("diet");
                        
                        String[] cuisines = cuisinesArray != null ? (String[]) cuisinesArray.getArray() : new String[0];
                        String[] allergies = allergiesArray != null ? (String[]) allergiesArray.getArray() : new String[0];
                        String[] diet = dietArray != null ? (String[]) dietArray.getArray() : new String[0];
                        Integer priceRange = rs.getObject("pricerangepref", Integer.class);
                        
                        return new UserPreferences(cuisines, allergies, priceRange, diet);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting user preferences: " + e.getMessage());
            e.printStackTrace();
        }
        return new UserPreferences(new String[0], new String[0], null, new String[0]);
    }

    public static class UserPreferences {
        private final String[] favoriteCuisines;
        private final String[] allergies;
        private final Integer priceRange;
        private final String[] diet;

        public UserPreferences(String[] favoriteCuisines, String[] allergies, Integer priceRange, String[] diet) {
            this.favoriteCuisines = favoriteCuisines;
            this.allergies = allergies;
            this.priceRange = priceRange;
            this.diet = diet;
        }

        public String[] getFavoriteCuisines() {
            return favoriteCuisines;
        }

        public String[] getAllergies() {
            return allergies;
        }

        public Integer getPriceRange() {
            return priceRange;
        }

        public String[] getDiet() {
            return diet;
        }

        public String toDisplayString() {
            StringBuilder sb = new StringBuilder("Your current preferences:\n\n");
            
            if (diet.length > 0) {
                sb.append("🥗 Diet: ").append(String.join(", ", diet)).append("\n");
            } else {
                sb.append("🥗 Diet: not set\n");
            }
            
            if (allergies.length > 0) {
                sb.append("⚠️ Allergies: ").append(String.join(", ", allergies)).append("\n");
            } else {
                sb.append("⚠️ Allergies: not set\n");
            }
            
            if (priceRange != null) {
                sb.append("💰 Price Level: ").append("$".repeat(priceRange)).append(" (").append(priceRange).append(")\n");
            } else {
                sb.append("💰 Price Level: not set\n");
            }
            
            if (favoriteCuisines.length > 0) {
                sb.append("❤️ Favorite Cuisines: ").append(String.join(", ", favoriteCuisines));
            } else {
                sb.append("❤️ Favorite Cuisines: not set");
            }
            
            return sb.toString();
        }
    }
}
