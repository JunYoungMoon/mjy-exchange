package com.mjy.coin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mjy.coin.dto.CoinOrderDTO;
import com.mjy.coin.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RedisService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        objectMapper = new ObjectMapper();
        // Java 8 날짜/시간 모듈 등록
        objectMapper.registerModule(new JavaTimeModule());
    }

    public void setValues(String key, String data) {
        ValueOperations<String, Object> values = redisTemplate.opsForValue();
        values.set(key, data);
    }

    public void setValues(String key, String data, Duration duration) {
        ValueOperations<String, Object> values = redisTemplate.opsForValue();
        values.set(key, data, duration);
    }

    @Transactional(readOnly = true)
    public String getValues(String key) {
        ValueOperations<String, Object> values = redisTemplate.opsForValue();

        if (values.get(key) == null) {
            return "false";
        }
        return (String) values.get(key);
    }

    public void deleteValues(String key) {
        redisTemplate.delete(key);
    }

    public void expireValues(String key, int timeout) {
        redisTemplate.expire(key, timeout, TimeUnit.MILLISECONDS);
    }

    public void setHashOps(String key, Map<String, String> data) {
        HashOperations<String, Object, Object> values = redisTemplate.opsForHash();
        values.putAll(key, data);
    }

    @Transactional(readOnly = true)
    public String getHashOps(String key, String hashKey) {
        HashOperations<String, Object, Object> values = redisTemplate.opsForHash();
        return Boolean.TRUE.equals(values.hasKey(key, hashKey)) ? (String) redisTemplate.opsForHash().get(key, hashKey) : "";
    }

    public void deleteHashOps(String key, String hashKey) {
        HashOperations<String, Object, Object> values = redisTemplate.opsForHash();
        values.delete(key, hashKey);
    }

    public boolean checkExistsValue(String value) {
        return !value.equals("false");
    }

    public Cursor<Map.Entry<String, String>> scanCursor(String key) {
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        // Redis의 스캔 기능 사용 (Cursor 생성)
        return hashOps.scan(key, ScanOptions.NONE);
    }

    public Map<String, String> convertStringToMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            System.err.println("Failed to convert string to map: " + e.getMessage());
            return null;
        }
    }

    public <T> T convertStringToObject(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert JSON to " + type.getSimpleName(), e);
        }
    }

    public String convertMapToString(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Error converting Map to String", e);
        }
    }

    public void updateOrderInRedis(CoinOrderDTO order) {
        try {
            // 1. Redis에서 기존 데이터 가져오기
            String orderData = getHashOps(order.getCoinName() + "-" + order.getMarketName(), order.getUuid());

            // 2. String 데이터를 Map으로 변환
            Map<String, String> orderDataMap = convertStringToMap(orderData);

            // 3. 필요한 데이터 업데이트
            orderDataMap.put("orderStatus", String.valueOf(order.getOrderStatus()));
            orderDataMap.put("matchedAt", String.valueOf(order.getMatchedAt()));
            orderDataMap.put("coinAmount", String.valueOf(order.getCoinAmount()));
            orderDataMap.put("matchIdx", String.valueOf(order.getMatchIdx()));
            orderDataMap.put("executionPrice", String.valueOf(order.getExecutionPrice()));

            // 4. Map 데이터를 다시 String으로 직렬화
            String updatedOrderData = convertMapToString(orderDataMap);

            // 5. 수정된 데이터를 Redis에 다시 저장 (Hash 구조 사용)
            setHashOps(order.getCoinName() + "-" + order.getMarketName(), Map.of(order.getUuid(), updatedOrderData));

        } catch (Exception e) {
            System.err.println("Failed to update order in Redis: " + e.getMessage());
        }
    }

    public void insertOrderInRedis(CoinOrderDTO order) {
        try {
            // Redis에 저장할 주문 데이터를 HashMap으로 저장
            Map<String, String> orderDataMap = new HashMap<>();

            // 기본 데이터 추가
            orderDataMap.put("uuid", String.valueOf(order.getUuid()));
            orderDataMap.put("coinName", String.valueOf(order.getCoinName()));
            orderDataMap.put("marketName", String.valueOf(order.getMarketName()));
            orderDataMap.put("coinAmount", String.valueOf(order.getCoinAmount()));
            orderDataMap.put("orderPrice", String.valueOf(order.getOrderPrice()));
            orderDataMap.put("orderType", String.valueOf(order.getOrderType()));
            orderDataMap.put("fee", String.valueOf(order.getFee()));
            orderDataMap.put("createdAt", String.valueOf(LocalDateTime.now()));
            orderDataMap.put("matchedAt", String.valueOf(order.getMatchedAt()));
            orderDataMap.put("memberId", String.valueOf(order.getMemberId()));
            orderDataMap.put("orderStatus", String.valueOf(order.getOrderStatus()));
            orderDataMap.put("matchIdx", String.valueOf(order.getMatchIdx()));
            orderDataMap.put("executionPrice", String.valueOf(order.getExecutionPrice()));

            // 주문 데이터를 JSON 문자열로 변환
            String jsonOrderData = convertMapToString(orderDataMap);

            // Redis에 주문 데이터 저장 (Hash 구조 사용)
            setHashOps(order.getCoinName() + "-" + order.getMarketName(), Map.of(order.getUuid(), jsonOrderData));

        } catch (Exception e) {
            System.err.println("Failed to insert order in Redis: " + e.getMessage());
        }
    }
}
