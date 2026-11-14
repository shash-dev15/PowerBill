package com.powerbill;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MongoConnectivityTest {
    @Test
    void connectsToConfiguredMongoDbWithJavaSyncDriver() {
        String uri = System.getenv().getOrDefault("POWERBILL_MONGODB_URI", "mongodb://127.0.0.1:27017");
        try (MongoClient client = MongoClients.create(uri)) {
            Document ping = client.getDatabase("admin").runCommand(new Document("ping", 1));
            assertEquals(1.0, ((Number) ping.get("ok")).doubleValue());
        }
    }
}
