package com.ibm.cloud.cache.redis;

import java.util.logging.Logger;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Component;

/**
 * Allows for modifying Redis cache instances to use a circuit breaker by utilizing Spring's Aspect Oriented Programming (AOP) support.
 */
@Aspect
@Component
public class CircuitBreakerRedisCacheAspect {
    
    private static final Logger logger = Logger.getLogger(CircuitBreakerRedisCacheAspect.class.getName());

	public CircuitBreakerRedisCacheAspect() {
	}
	
	/**
	 * Wraps <code>RedisCache</code> instances with <code>CircuitBreakerRedisCache</code>. Every time a cache manager 
	 * returns a cache instance, this method checks if it is a <code>RedisCache</code> and if so, wraps it with our circuit breaker 
	 * implementation.
	 * 
	 * @return A <code>CircuitBreakerRedisCache</code> if the underlying cache instance is of type <code>RedisCache</code>; otherwise 
	 * returns the underlying cache instance.
	 */
	@Around("execution(* org.springframework.cache.support.AbstractCacheManager.getCache(..))")
    public Cache beforeCacheGet(ProceedingJoinPoint proceedingJoinPoint) {
        try {
            Cache cache = (Cache) proceedingJoinPoint.proceed(proceedingJoinPoint.getArgs());
            if (cache instanceof RedisCache) {
            	logger.finest("Creating CircuitBreakerRedisCache");
                return new CircuitBreakerRedisCache((RedisCache) cache);
            } else {
                return cache;
            }
        } catch (Throwable ex) {
            return null;
        }
    }

}
