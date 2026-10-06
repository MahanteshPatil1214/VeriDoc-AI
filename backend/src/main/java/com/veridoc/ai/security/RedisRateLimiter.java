package com.veridoc.ai.security;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.veridoc.ai.common.error.AppException;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.RateLimitProperties;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Fixed-window rate limiter backed by Redis.
 *
 * <p>Applied to the expensive operations (login, upload, chat, embedding
 * generation). Limits are configurable per operation.
 *
 * <p>If Redis is unavailable the limiter fails open and logs a warning: a
 * Redis outage should degrade throttling quality rather than take down the
 * product.
 */
@Component
public class RedisRateLimiter {

    private static final String KEY_PREFIX = "veridoc:rl:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    public RedisRateLimiter(StringRedisTemplate redis,
                            RateLimitProperties properties,
                            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public void check(String bucket, String subject, String limitSpec) {
        if (!properties.enabled()) {
            return;
        }
        Limit limit = Limit.parse(limitSpec);
        String key = KEY_PREFIX + bucket + ":" + subject;

        long windowStart = System.currentTimeMillis() / limit.period().toMillis();
        try {
            Long used = redis.opsForValue().increment(key + ":" + windowStart);
            if (used != null && used == 1L) {
                redis.expire(key + ":" + windowStart, limit.period().plusSeconds(1));
            }
            long count = used == null ? 0L : used;
            if (count > limit.permits()) {
                meterRegistry.counter("veridoc.rate.limit.rejected", "bucket", bucket).increment();
                throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED,
                        "Too many requests. Please slow down and try again shortly.");
            }
        } catch (AppException e) {
            throw e;
        } catch (RuntimeException e) {
            // Fail open: a Redis outage must not become a total outage.
            meterRegistry.counter("veridoc.rate.limit.redis_errors", "bucket", bucket).increment();
            org.slf4j.LoggerFactory.getLogger(RedisRateLimiter.class)
                    .warn("Rate limiter unavailable for bucket={}; failing open", bucket);
        }
    }

    private record Limit(int permits, Duration period) {

        static Limit parse(String spec) {
            int slash = spec.indexOf('/');
            if (slash <= 0 || slash == spec.length() - 1) {
                throw new IllegalArgumentException("Invalid rate limit: " + spec);
            }
            int permits = Integer.parseInt(spec.substring(0, slash).trim());
            Duration period = Duration.parse(spec.substring(slash + 1).trim());
            return new Limit(permits, period);
        }
    }
}