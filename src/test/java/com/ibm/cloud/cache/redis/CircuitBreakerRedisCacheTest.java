package com.ibm.cloud.cache.redis;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;

public class CircuitBreakerRedisCacheTest {
    
    private static final Logger logger = Logger.getLogger(CircuitBreakerRedisCacheTest.class.getName());
    
    private static final String TEST_KEY = "test";
    
    public static final String CB_NAME = "testRedisCB";
    public static final String CB_CONFIG_NAME = "testRedisCBConfig";
    public static final int CB_FAILURE_RATE_THRESHOLD = 100;
    public static final int CB_SLIDING_WINDOW_SIZE = 6;
    public static final int CB_MIN_NUM_CALLS = 6;
    public static final Duration CB_WAIT_DURATION_OPEN_STATE = Duration.ofSeconds(3);
    public static final int CB_NUM_CALLS_HALF_OPEN_STATE = 6;
    public static final Duration CB_SLOW_CALL_DURATION = Duration.ofMillis(1500); // 1.5 secs
    public static final int CB_SLOW_RATE_THRESHOLD = 50;
    
    @Mock
    private RedisCacheWriter cacheWriter;
    @Mock
    private RedisCacheConfiguration redisCacheConfig;
    @Mock
    private ConversionService conversionService;
    @Mock
    private SerializationPair<String> keySerializationPair;
    @Mock
    private SerializationPair<Object> valueSerializationPair;
    
    private RedisCache redisCache;
    
    private CircuitBreakerRedisCacheManager cacheMgr;
    
    private CircuitBreakerRedisCache cache;
    
    private CircuitBreaker cb;

    public CircuitBreakerConfig testCircuitBreakerConfig() {
        CircuitBreakerConfig cbc = CircuitBreakerConfig.custom()
                .failureRateThreshold(CB_FAILURE_RATE_THRESHOLD)
                .slowCallRateThreshold(CB_SLOW_RATE_THRESHOLD)
                .waitDurationInOpenState(CB_WAIT_DURATION_OPEN_STATE)
                .slowCallDurationThreshold(CB_SLOW_CALL_DURATION)
                .permittedNumberOfCallsInHalfOpenState(CB_NUM_CALLS_HALF_OPEN_STATE)
                .minimumNumberOfCalls(CB_MIN_NUM_CALLS)
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(CB_SLIDING_WINDOW_SIZE) // how many calls to collect in a single aggregation
                .build();
        return cbc;
    }
    
    public CircuitBreaker defaultCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME, CB_CONFIG_NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event -> this.circuitBreakerStateTransition(event, circuitBreaker));
        return circuitBreaker;
    }
    
    private void circuitBreakerStateTransition(CircuitBreakerOnStateTransitionEvent event, CircuitBreaker cb) {
    	logger.warning("CircuitBreaker " + event.getCircuitBreakerName() 
	        + " transitioned from " + event.getStateTransition().getFromState() 
	        + " to " + event.getStateTransition().getToState());
    	CircuitBreakerRedisCache.logCBMetrics(cb, Level.WARNING);
    }

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
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Mockito.when(this.redisCacheConfig.getConversionService()).thenReturn(this.conversionService);
        Mockito.when(this.redisCacheConfig.getKeySerializationPair()).thenReturn(this.keySerializationPair);
        Mockito.when(this.redisCacheConfig.getValueSerializationPair()).thenReturn(this.valueSerializationPair);
        Mockito.when(this.conversionService.canConvert(Mockito.any(TypeDescriptor.class), Mockito.any(TypeDescriptor.class))).thenReturn(false);
        
        this.cacheMgr = new CircuitBreakerRedisCacheManager(this.cacheWriter, this.redisCacheConfig);
        this.redisCache = this.cacheMgr.createBaseRedisCache("testCBRedisCache", this.redisCacheConfig);
        
        CircuitBreakerConfig cbc = this.testCircuitBreakerConfig();
        CircuitBreakerRegistry cbr = this.defaultCircuitBreakerRegistry(cbc);
        this.cb = this.defaultCircuitBreaker(cbr);
        this.cache = new CircuitBreakerRedisCache(this.redisCache, this.cb);
    }
    
    @SuppressWarnings("unchecked")
    protected void setUpForFailure() {
        Mockito.when(this.cacheWriter.get(Mockito.anyString(), Mockito.any())).thenThrow(IOException.class);
        Mockito.when(this.conversionService.canConvert(Mockito.any(TypeDescriptor.class), Mockito.any(TypeDescriptor.class))).thenReturn(false);
        Mockito.when(this.conversionService.convert(Mockito.any(), Mockito.any(Class.class))).thenThrow(IOException.class);
        Mockito.when(this.keySerializationPair.write(Mockito.anyString())).thenThrow(IOException.class);
    }
    
    protected void setUpForSuccess() {
        Mockito.reset(this.cacheWriter, this.keySerializationPair);
        Mockito.when(this.cacheWriter.get(Mockito.anyString(), Mockito.any())).thenReturn(TEST_KEY.getBytes());
        Mockito.when(this.cacheWriter.putIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(TEST_KEY.getBytes());
        Mockito.when(this.keySerializationPair.write(Mockito.anyString())).thenReturn(ByteBuffer.wrap(TEST_KEY.getBytes()));
        Mockito.when(this.keySerializationPair.read(Mockito.any(ByteBuffer.class))).thenReturn(TEST_KEY);
        Mockito.when(this.valueSerializationPair.write(Mockito.anyString())).thenReturn(ByteBuffer.wrap(TEST_KEY.getBytes()));
        Mockito.when(this.valueSerializationPair.read(Mockito.any(ByteBuffer.class))).thenReturn(TEST_KEY);
        Mockito.when(this.conversionService.convert(Mockito.any(), Mockito.eq(byte[].class))).thenReturn(TEST_KEY.getBytes());
    }
    
    protected void setUpForSlowFailure() {
        this.setUpForSuccess();
        Mockito.when(this.cacheWriter.get(Mockito.anyString(), Mockito.any())).thenAnswer(new Answer<byte[]>() {

			@Override
			public byte[] answer(InvocationOnMock invocation) throws Throwable {
				return slowReturn(TEST_KEY.getBytes());
			}
        	
        });
    }
    
    public static <T> T slowReturn(T val) {
    	long sleep = CB_SLOW_CALL_DURATION.toMillis() + 500;
    	return slowReturn(val, sleep);
    }
    
    public static <T> T slowReturn(T val, long sleep) {
    	logger.info("slowReturn for " + sleep + " ms");
    	try {
			Thread.sleep(sleep);
		} catch (InterruptedException e) {
		}
		return val;
    }

    @Test
    public void testCircuitBreakerOpened() {
        this.setUpForFailure();

        for (int i = 0; i < CB_SLIDING_WINDOW_SIZE; i++) {
            if (this.cb.getState().equals(State.CLOSED)) {
                try {
                    this.cache.get(TEST_KEY);
                    fail("Expected exception from redis cache");
                } catch (Exception e) {
                    System.out.println("Exception from cache get: " + e.getMessage());
                }
                try {
                    this.cache.get(TEST_KEY, () -> TEST_KEY);
                    fail("Expected exception from redis cache");
                } catch (Exception e) {
                    System.out.println("Exception from cache get: " + e.getMessage());
                }
                try {
                    this.cache.put(TEST_KEY, TEST_KEY);
                    fail("Expected exception from redis cache");
                } catch (Exception e) {
                    System.out.println("Exception from cache put: " + e.getMessage());
                }
                try {
                    this.cache.putIfAbsent(TEST_KEY, TEST_KEY);
                    fail("Expected exception from redis cache");
                } catch (Exception e) {
                    System.out.println("Exception from cache put: " + e.getMessage());
                }
                try {
                    this.cache.evict(TEST_KEY);
                    fail("Expected exception from redis cache");
                } catch (Exception e) {
                    System.out.println("Exception from cache evict: " + e.getMessage());
                }
                try {
                    this.cache.clear();
                    fail("Expected exception from redis cache");
                } catch (Exception e) {
                    System.out.println("Exception from cache clear: " + e.getMessage());
                }
            }
            
            // circuit should be open now
            assertEquals("Unexpected circuit breaker state", CircuitBreaker.State.OPEN, this.cb.getState());
            try {
                ValueWrapper value = this.cache.get(TEST_KEY);
                assertEquals("Unexpected value", null, value);
            } catch (Exception e) {
                e.printStackTrace();
                fail("Unexpected exception from redis cache: " + e.getMessage());
            }

            try {
                this.cache.clear();
            } catch (Exception e) {
                e.printStackTrace();
                fail("Unexpected exception from redis cache: " + e.getMessage());
            }
        }
    }

    @Test
    public void testCircuitBreakerClosed() {
        this.setUpForFailure();
        
        for (int i = 0; i < CB_SLIDING_WINDOW_SIZE; i++) {
            try {
                this.cache.get(TEST_KEY);
                fail("Expected exception from redis cache");
            } catch (Exception e) {
                System.out.println("Exception from cache get: " + e.getMessage());
            }
        }
        
        // circuit should be open now
        for (int i = 0; i < CB_SLIDING_WINDOW_SIZE; i++) {
            try {
                ValueWrapper value = this.cache.get(TEST_KEY);
                assertEquals("Unexpected value", null, value);
            } catch (Exception e) {
                e.printStackTrace();
                fail("Unexpected exception from redis cache: " + e.getMessage());
            }
            
            assertEquals("Unexpected circuit breaker state", CircuitBreaker.State.OPEN, this.cb.getState());
        }
        
        this.setUpForSuccess();
        while (!this.cb.getState().equals(State.CLOSED)) {
            try {
                ValueWrapper value = this.cache.get(TEST_KEY);
                if (this.cb.getState().equals(State.HALF_OPEN)) {
                    assertNotNull("Return value is null", value);
                    assertEquals("Unexpected value type",SimpleValueWrapper.class, value.getClass());
                    assertEquals("Unexpected value", TEST_KEY, ((SimpleValueWrapper)value).get());
                } else {
                    if (this.cb.getState().equals(State.HALF_OPEN) || this.cb.getState().equals(State.CLOSED)) {
                        continue;
                    }
                    assertNull("Unexpected value", value);
                }
            } catch (Exception e) {
                e.printStackTrace();
                fail("Unexpected exception from redis cache: " + e.getMessage());
            }
        }
        
        // circuit should be closed now
        assertEquals("Unexpected circuit breaker state", CircuitBreaker.State.CLOSED, this.cb.getState());
        try {
            ValueWrapper value = this.cache.get(TEST_KEY);
            assertNotNull("Return value is null", value);
            assertEquals("Unexpected value type",SimpleValueWrapper.class, value.getClass());
            assertEquals("Unexpected value", TEST_KEY, ((SimpleValueWrapper)value).get());
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache: " + e.getMessage());
        }
        try {
            String val = this.cache.get(TEST_KEY, () -> TEST_KEY);
            assertNotNull("Return value is null", val);
            assertEquals("Unexpected value", TEST_KEY, val);
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache get: " + e.getMessage());
        }
        try {
            this.cache.put(TEST_KEY, TEST_KEY);
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache put: " + e.getMessage());
        }
        try {
            ValueWrapper value = this.cache.putIfAbsent(TEST_KEY, TEST_KEY);
            assertNotNull("Return value is null", value);
            assertEquals("Unexpected value type",SimpleValueWrapper.class, value.getClass());
            assertEquals("Unexpected value", TEST_KEY, ((SimpleValueWrapper)value).get());
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache put: " + e.getMessage());
        }
        try {
            this.cache.evict(TEST_KEY);
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache evict: " + e.getMessage());
        }
        try {
            this.cache.clear();
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache clear: " + e.getMessage());
        }
    }

    @Test
    public void testCircuitBreakerOpenedSlow() {
        this.setUpForSlowFailure();

        for (int i = 0; i < CB_SLIDING_WINDOW_SIZE; i++) {
            if (this.cb.getState().equals(State.CLOSED)) {
                try {
                    ValueWrapper value = this.cache.get(TEST_KEY);
                    assertNotNull("Return value is null", value);
                    assertEquals("Unexpected value type",SimpleValueWrapper.class, value.getClass());
                    assertEquals("Unexpected value", TEST_KEY, ((SimpleValueWrapper)value).get());
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("Unexpected exception from redis cache: " + e.getMessage());
                }
            }
        }
        
        // circuit should be open now
        assertEquals("Unexpected circuit breaker state", CircuitBreaker.State.OPEN, this.cb.getState());
        try {
            ValueWrapper value = this.cache.get(TEST_KEY);
            assertEquals("Unexpected value", null, value);
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache: " + e.getMessage());
        }

        try {
            this.cache.clear();
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unexpected exception from redis cache: " + e.getMessage());
        }
    }

}
