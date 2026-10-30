package ru.repaircalc.storage;

import com.mongodb.*;
import com.mongodb.client.*;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class MongoConnectionConfiguration {
  @Bean
  public MongoClient mongoClient(Environment env) {
    String uri = env.getRequiredProperty("spring.mongodb.uri"),
        user = env.getProperty("MONGO_USER", ""),
        password = env.getProperty("MONGO_PASSWORD", "");
    var connection = new ConnectionString(uri);
    if (connection.getDatabase() == null)
      throw new IllegalArgumentException("MONGO_URI must name a database");
    int selection = env.getProperty("MONGO_SERVER_SELECTION_TIMEOUT_MS", Integer.class, 3000),
        connect = env.getProperty("MONGO_CONNECT_TIMEOUT_MS", Integer.class, 3000),
        read = env.getProperty("MONGO_READ_TIMEOUT_MS", Integer.class, 10000);
    if (selection < 1 || connect < 1 || read < 1)
      throw new IllegalArgumentException("Invalid Mongo timeout");
    var builder =
        MongoClientSettings.builder()
            .applyConnectionString(connection)
            .applyToClusterSettings(s -> s.serverSelectionTimeout(selection, TimeUnit.MILLISECONDS))
            .applyToSocketSettings(
                s ->
                    s.connectTimeout(connect, TimeUnit.MILLISECONDS)
                        .readTimeout(read, TimeUnit.MILLISECONDS));
    if (!user.isBlank()) {
      if (password.isBlank() || connection.getCredential() != null)
        throw new IllegalArgumentException(
            "Use MONGO_USER/MONGO_PASSWORD with a credential-free URI");
      builder.credential(
          MongoCredential.createCredential(user, connection.getDatabase(), password.toCharArray()));
    }
    return MongoClients.create(builder.build());
  }
}
