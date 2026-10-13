package com.ford.service.config.secrets;

public interface VaultSecretValueFetcher extends AutoCloseable {

    String fetchSecretValue(String secretId);

    @Override
    default void close() {}
}
