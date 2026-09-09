package com.docente.proyectoejemplo.products;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
public class DynamoDbConfig {

    @Value("${app.dynamo.table-name}")
    private String tableName;

    // Región y credenciales se resuelven automáticamente por el SDK:
    // - Región: variable de entorno AWS_REGION
    // - Credenciales: IAM Role de la instancia EC2 (EC2-DynamoDB-Access-Role), sin llaves estáticas
    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder().build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    public DynamoDbTable<Product> productTable(DynamoDbEnhancedClient enhancedClient) {
        return enhancedClient.table(tableName, TableSchema.fromBean(Product.class));
    }
}
