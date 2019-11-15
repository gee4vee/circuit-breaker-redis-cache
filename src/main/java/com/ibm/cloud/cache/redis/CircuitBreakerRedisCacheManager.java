package com.ibm.cloud.cache.redis;
/**
 * 
 */


import java.util.Map;

import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * @author gvalenc
 *
 */
public class CircuitBreakerRedisCacheManager extends RedisCacheManager {
    

    /**
     * @param cacheWriter
     * @param defaultCacheConfiguration
     */
    public CircuitBreakerRedisCacheManager(RedisCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration) {
        super(cacheWriter, defaultCacheConfiguration);
    }

    /**
     * @param cacheWriter
     * @param defaultCacheConfiguration
     * @param initialCacheNames
     */
    public CircuitBreakerRedisCacheManager(RedisCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration, String... initialCacheNames) {
        super(cacheWriter, defaultCacheConfiguration, initialCacheNames);
    }

    /**
     * @param cacheWriter
     * @param defaultCacheConfiguration
     * @param initialCacheConfigurations
     */
    public CircuitBreakerRedisCacheManager(RedisCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            Map<String, RedisCacheConfiguration> initialCacheConfigurations) {
        super(cacheWriter, defaultCacheConfiguration, initialCacheConfigurations);
    }

    /**
     * @param cacheWriter
     * @param defaultCacheConfiguration
     * @param allowInFlightCacheCreation
     * @param initialCacheNames
     */
    public CircuitBreakerRedisCacheManager(RedisCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration, boolean allowInFlightCacheCreation,
            String... initialCacheNames) {
        super(cacheWriter, defaultCacheConfiguration, allowInFlightCacheCreation, initialCacheNames);
    }

    /**
     * @param cacheWriter
     * @param defaultCacheConfiguration
     * @param initialCacheConfigurations
     * @param allowInFlightCacheCreation
     */
    public CircuitBreakerRedisCacheManager(RedisCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            Map<String, RedisCacheConfiguration> initialCacheConfigurations, boolean allowInFlightCacheCreation) {
        super(cacheWriter, defaultCacheConfiguration, initialCacheConfigurations, allowInFlightCacheCreation);
    }
    
    @Override
    protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfig) {
        return new CircuitBreakerRedisCache(super.createRedisCache(name, cacheConfig));
    }
    
    public RedisCache createBaseRedisCache(String name, RedisCacheConfiguration cacheConfig) {
        return super.createRedisCache(name, cacheConfig);
    }

}
