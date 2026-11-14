package com.powerbill;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MongoCrudTest {
    @Test void temporaryCollectionSupportsCreateReadUpdateDelete() {
        String uri = System.getenv().getOrDefault("POWERBILL_MONGODB_URI", "mongodb://127.0.0.1:27017");
        String databaseName = "powerbill_crud_test_" + UUID.randomUUID().toString().replace("-", "");
        String collectionName = "records";
        try (MongoClient client = MongoClients.create(uri)) {
            var collection = client.getDatabase(databaseName).getCollection(collectionName);
            try {
                collection.insertOne(new Document("key", "consumer-1").append("value", "before"));
                assertEquals("before", collection.find(new Document("key", "consumer-1")).first().getString("value"));
                collection.updateOne(new Document("key", "consumer-1"), new Document("$set", new Document("value", "after")));
                assertEquals("after", collection.find(new Document("key", "consumer-1")).first().getString("value"));
                assertNotNull(collection.find(new Document("key", "consumer-1")).first());
                assertEquals(1, collection.deleteOne(new Document("key", "consumer-1")).getDeletedCount());
                assertEquals(0, collection.countDocuments());
            } finally {
                client.getDatabase(databaseName).drop();
            }
        }
    }
}
