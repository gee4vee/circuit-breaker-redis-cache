package com.ibm.cloud.cache.redis;
/**
 * 
 */


import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.cache.RedisCache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.vavr.CheckedFunction0;
import io.vavr.control.Try;

/**
 * RedisCache implementation that uses a resilient4j circuit breaker to bypass the Redis instance calls if the circuit 
 * is open. This allows us to not be sensitive to ICD Redis instabilities. If the call to ICD Redis fails, Spring Cache 
 * will treat it as a cache miss and continue executing the cached method normally.
 */
public class CircuitBreakerRedisCache extends RedisCache {
    
    private static final Logger logger = Logger.getLogger(CircuitBreakerRedisCache.class.getName());
    
    private RedisCache redisCache;
    
    @Autowired
    private CircuitBreaker circuitBreaker;
    
    /**
     * @param name
     * @param cacheWriter
     * @param cacheConfig
     */
    public CircuitBreakerRedisCache(RedisCache redisCache) {
        super(redisCache.getName(), redisCache.getNativeCache(), redisCache.getCacheConfiguration());
        this.redisCache = redisCache;
    }
    
    public CircuitBreakerRedisCache(RedisCache redisCache, CircuitBreaker cb) {
        this(redisCache);
        this.circuitBreaker = cb;
    }
    
    @Override
    public ValueWrapper get(Object key) {
        CheckedFunction0<ValueWrapper> checkedFunction = this.circuitBreaker.decorateCheckedSupplier(() -> this.redisCache.get(key));
        Try<ValueWrapper> result = Try.of(checkedFunction).recoverWith(CallNotPermittedException.class, Try.success(null));
        return result.get();
    }
    
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        CheckedFunction0<T> checkedFunction = this.circuitBreaker.decorateCheckedSupplier(() -> this.redisCache.get(key, valueLoader));
        Try<T> result = Try.of(checkedFunction).recoverWith(CallNotPermittedException.class, Try.success(null));
        return result.get();
    }
    
    @Override
    public void put(Object key, Object value) {
        Runnable decorateRunnable = this.circuitBreaker.decorateRunnable(() -> this.redisCache.put(key, value));
        Try<Void> result = Try.runRunnable(decorateRunnable).recover(CallNotPermittedException.class, x -> null);
        if (result.isFailure()) {
            Throwable cause = result.getCause();
            logger.log(Level.WARNING, "Failure from RedisCache.put()", cause);
        }
        result.get();
    }
    
    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        CheckedFunction0<ValueWrapper> checkedFunction = this.circuitBreaker.decorateCheckedSupplier(() -> this.redisCache.putIfAbsent(key, value));
        Try<ValueWrapper> result = Try.of(checkedFunction).recoverWith(CallNotPermittedException.class, Try.success(null));
        return result.get();
    }
    
    @Override
    public void evict(Object key) {
        Runnable decorateRunnable = this.circuitBreaker.decorateRunnable(() -> this.redisCache.evict(key));
        Try<Void> result = Try.runRunnable(decorateRunnable).recover(CallNotPermittedException.class, x -> null);
        if (result.isFailure()) {
            Throwable cause = result.getCause();
            logger.log(Level.WARNING, "Failure from RedisCache.evict()", cause);
        }
        result.get();
    }
    
    @Override
    public void clear() {
        Runnable decorateRunnable = this.circuitBreaker.decorateRunnable(() -> this.redisCache.clear());
        Try<Void> result = Try.runRunnable(decorateRunnable).recover(CallNotPermittedException.class, x -> null);
        if (result.isFailure()) {
            Throwable cause = result.getCause();
            logger.log(Level.WARNING, "Failure from RedisCache.clear()", cause);
        }
        result.get();
    }

}
