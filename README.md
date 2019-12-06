# Circuit Breaker Redis Cache
This project provides a wrapper around Spring Data Redis `RedisCache` that incorporates a Circuit Breaker from the [resilience4j](https://github.com/resilience4j/resilience4j) project. This is useful in cases where the Redis server is down or slow and the application needs to continue servicing requests without a cache, albeit more slowly. In certain situations, Redis server instability coupled with Spring Data Redis Cache usage can lead to application instability. A circuit breaker provides an elegant solution in these scenarios.

The class `com.ibm.cloud.cache.redis.CircuitBreakerRedisCache` wraps all calls to the underlying `RedisCache` with `io.github.resilience4j.circuitbreaker.CircuitBreaker` decoration such that if the calls fail enough to open the circuit, the calls will be subsequently bypassed until the circuit reopens. 

## How to use
The wrapping is implemented via [Spring Aspects](https://docs.spring.io/spring/docs/current/spring-framework-reference/core.html#aop-atconfigurable) such that whenever a `RedisCache` instance is requested from the cache manager, it is wrapped by `CircuitBreakerRedisCache`. Therefore an application only needs the following to make use of this cache:
1. Add this project as a dependency.
2. Create a `@Bean`-annotated methods that will create the following objects: `io.github.resilience4j.circuitbreaker.CircuitBreakerConfig`, `io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry`, `io.github.resilience4j.circuitbreaker.CircuitBreaker`. These methods typically reside in an extension of `org.springframework.cache.annotation.CachingConfigurerSupport` when utilizing [Spring Cache](https://spring.io/guides/gs/caching/).

## Sample configuration code
Example Circuit Breaker configuration beans follow. With this code:
- The Circuit Breaker sliding window is configured to be time-based, with a duration of 2 minutes. 
- The failure and slow call rates are both set to 50%.
- A minimum of 10 calls must be made before the circuit breaker can begin opening.

If 50% of the calls within the sliding window period fail or are slower than 1.5 seconds, the circuit is open and `RedisCache` calls are not made. At this point `CircuitBreakerRedisCache` behaves as a no-op cache, triggering cache misses. After 3 minutes, the circuit goes to `HALF_OPEN` state, allowing up to 50 `RedisCache` calls to see whether they succeed. If at least 50% calls do succeed, then the circuit is closed and `RedisCache` calls are made normally.

```java
// ...
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;

// ...

public static final String CB_NAME = "myAppCB";
public static final String CB_CONFIG_NAME = "myAppCBConfig";
public static final int CB_SLOW_RATE_THRESHOLD = 50;
public static final int CB_FAILURE_RATE_THRESHOLD = 50;
public static final Duration CB_SLOW_CALL_DURATION = Duration.ofMillis(1500); // 1.5 secs
public static final int CB_NUM_CALLS_HALF_OPEN_STATE = 50;
public static final int CB_MIN_NUM_CALLS = 10;
public static final Duration CB_WAIT_DURATION_OPEN_STATE = Duration.ofMillis(3*60*1000); // 3 minutes
public static final int CB_SLIDING_WINDOW_SIZE = 60*2; // 2 minutes

@Bean
@Lazy
public CircuitBreaker defaultCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
    CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME, CB_CONFIG_NAME);
    circuitBreaker.getEventPublisher().onStateTransition(event -> this.circuitBreakerStateTransition(event, circuitBreaker));
    return circuitBreaker;
}

private void circuitBreakerStateTransition(CircuitBreakerOnStateTransitionEvent event, CircuitBreaker cb) {
    logger.warning("CircuitBreaker " + event.getCircuitBreakerName() 
      + " transitioned from " + event.getStateTransition().getFromState() 
      + " to " + event.getStateTransition().getToState());
    logCBMetrics(cb, Level.WARNING);
}

public static void logCBMetrics(CircuitBreaker cb, Level loggerLevel) {
    Metrics cbMetrics = cb.getMetrics();
    StringBuilder sb = new StringBuilder("CircuitBreaker Metrics for " + cb + ":\n");
    sb.append("\tNum successful calls: ").append(cbMetrics.getNumberOfSuccessfulCalls()).append("\n");
    sb.append("\tNum failed calls: ").append(cbMetrics.getNumberOfFailedCalls()).append("\n");
    sb.append("\tNum slow calls: ").append(cbMetrics.getNumberOfSlowCalls()).append("\n");
    sb.append("\tSlow call rate: ").append(cbMetrics.getSlowCallRate()).append("\n");
    sb.append("\tNum not permitted calls: ").append(cbMetrics.getNumberOfNotPermittedCalls()).append("\n");
    logger.log(loggerLevel, sb.toString());
}

@Bean
@Lazy
public CircuitBreakerRegistry defaultCircuitBreakerRegistry(CircuitBreakerConfig builtCBC) {
    CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();
    CircuitBreakerConfig cbc;
    Optional<CircuitBreakerConfig> cbco = cbr.getConfiguration(CB_CONFIG_NAME);
    if (!cbco.isPresent()) {
      cbc = builtCBC;
      cbr.addConfiguration(CB_CONFIG_NAME, cbc);
    }
    return cbr;
}

@Bean
@Lazy
public CircuitBreakerConfig defaultCircuitBreakerConfig() {
    CircuitBreakerConfig cbc = CircuitBreakerConfig.custom()
          .failureRateThreshold(CB_FAILURE_RATE_THRESHOLD)
          .slowCallRateThreshold(CB_SLOW_RATE_THRESHOLD)
          .waitDurationInOpenState(CB_WAIT_DURATION_OPEN_STATE)
          .slowCallDurationThreshold(CB_SLOW_CALL_DURATION)
          .permittedNumberOfCallsInHalfOpenState(CB_NUM_CALLS_HALF_OPEN_STATE)
          .minimumNumberOfCalls(CB_MIN_NUM_CALLS)
          .slidingWindowType(SlidingWindowType.TIME_BASED)
          .slidingWindowSize(CB_SLIDING_WINDOW_SIZE)
          .build();
    return cbc;
}
```
