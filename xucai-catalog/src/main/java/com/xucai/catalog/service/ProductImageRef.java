package com.xucai.catalog.service;

/** Only server-created object references can become application image URLs. */
public final class ProductImageRef {
    private ProductImageRef() {}
    public static boolean validKey(String key) {
        return key != null && key.matches("products/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)");
    }
    public static String url(long productId, String stored) {
        if (stored != null && stored.startsWith("oss:")) {
            String key = stored.substring(4);
            return validKey(key) ? "/api/catalog/products/" + productId + "/image/" + key.substring(9) : null;
        }
        return stored;
    }
    public static String keepUploaded(long productId, String submitted, String stored) {
        if (stored != null && stored.startsWith("oss:") && java.util.Objects.equals(submitted, url(productId, stored))) return stored;
        return submitted;
    }
}
