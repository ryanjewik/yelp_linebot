package com.ryanhideo.linebot.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public class SignatureUtil {

    public static boolean isValidSignature(String body, String channelSecret, String signatureHeader) {
        if (signatureHeader == null || channelSecret == null) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec =
                    new SecretKeySpec(channelSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(hash);

            return encoded.equals(signatureHeader);
        } catch (Exception e) {
            return false;
        }
    }
}
