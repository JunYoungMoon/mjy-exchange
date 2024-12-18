package com.mjy.coin.service;

import com.mjy.coin.dto.CoinOrderDTO;
import com.mjy.coin.enums.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.PriorityQueue;

import static com.mjy.coin.enums.OrderStatus.*;
import static com.mjy.coin.enums.OrderType.BUY;
import static com.mjy.coin.enums.OrderType.SELL;
import static com.mjy.coin.util.CommonUtil.generateUniqueKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingOrderMatcherServiceV2Test {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private PendingOrderMatcherServiceV2 pendingOrderMatcherService;

    private CoinOrderDTO createOrder(OrderType type, String price, String amount) {
        CoinOrderDTO order = new CoinOrderDTO();
        order.setUuid(generateUniqueKey("Order"));
        order.setOrderType(type);
        order.setOrderPrice(new BigDecimal(price));
        order.setCoinAmount(new BigDecimal(amount));
        order.setOrderStatus(PENDING);
        return order;
    }

    @Test
    public void testCalculateRemainingQuantity() {
        // given
        CoinOrderDTO order = createOrder(BUY, "110", "1.5");
        CoinOrderDTO oppositeOrder = createOrder(BUY, "110", "1.5");

        // when
        BigDecimal remaining = pendingOrderMatcherService.calculateRemainingQuantity(order, oppositeOrder);

        // then
        assertEquals(0, remaining.compareTo(BigDecimal.ZERO));
    }

    @Test
    public void testIsCompleteMatch() {
        // given
        BigDecimal zeroQuantity = BigDecimal.ZERO;
        BigDecimal nonZeroQuantity = new BigDecimal("1.5");

        // when & then
        assertTrue(pendingOrderMatcherService.isCompleteMatch(zeroQuantity), "남은 수량이 0인 경우 true 반환");
        assertFalse(pendingOrderMatcherService.isCompleteMatch(nonZeroQuantity), "남은 수량이 양수인 경우 false 반환");
    }


    @Test
    public void testIsOversizeMatch() {
        // given
        BigDecimal positiveQuantity = new BigDecimal("1.5");
        BigDecimal zeroQuantity = BigDecimal.ZERO;
        BigDecimal negativeQuantity = new BigDecimal("-1.5");

        // when & then
        assertTrue(pendingOrderMatcherService.isOversizeMatch(positiveQuantity), "남은 수량이 양수인 경우 true 반환");
        assertFalse(pendingOrderMatcherService.isOversizeMatch(zeroQuantity), "남은 수량이 0인 경우 false 반환");
        assertFalse(pendingOrderMatcherService.isOversizeMatch(negativeQuantity), "남은 수량이 음수인 경우 false 반환");
    }

    @Test
    public void testIsUndersizedMatch() {
        // given
        BigDecimal negativeQuantity = new BigDecimal("-1.5");
        BigDecimal zeroQuantity = BigDecimal.ZERO;
        BigDecimal positiveQuantity = new BigDecimal("1.5");

        // when & then
        assertTrue(pendingOrderMatcherService.isUndersizedMatch(negativeQuantity), "남은 수량이 음수인 경우 true 반환");
        assertFalse(pendingOrderMatcherService.isUndersizedMatch(zeroQuantity), "남은 수량이 0인 경우 false 반환");
        assertFalse(pendingOrderMatcherService.isUndersizedMatch(positiveQuantity), "남은 수량이 양수인 경우 false 반환");
    }

    @Test
    public void testCanMatchOrders(){
        // Case 1: BUY 타입, 가격 일치, 수량 > 0 => true 반환
        CoinOrderDTO buyOrder = createOrder(BUY, "100", "1.5");
        CoinOrderDTO sellOrder = createOrder(SELL, "90", "1.5");
        assertTrue(pendingOrderMatcherService.canMatchOrders(buyOrder, sellOrder), "BUY 주문이 SELL 주문과 가격 조건을 만족해야 함");

        // Case 2: SELL 타입, 가격 일치, 수량 > 0 => true 반환
        CoinOrderDTO sellOrder2 = createOrder(SELL, "90", "1.5");
        CoinOrderDTO buyOrder2 = createOrder(BUY, "100", "1.5");
        assertTrue(pendingOrderMatcherService.canMatchOrders(sellOrder2, buyOrder2), "SELL 주문이 BUY 주문과 가격 조건을 만족해야 함");

        // Case 3: BUY 타입, 가격 불일치 => false 반환
        CoinOrderDTO buyOrderFail = createOrder(BUY, "80", "1.5");
        CoinOrderDTO sellOrderFail = createOrder(SELL, "90", "1.5");
        assertFalse(pendingOrderMatcherService.canMatchOrders(buyOrderFail, sellOrderFail), "BUY 주문이 SELL 주문의 가격 조건을 만족하지 않아야 함");

        // Case 4: SELL 타입, 가격 불일치 => false 반환
        CoinOrderDTO sellOrderFail2 = createOrder(SELL, "110", "1.5");
        CoinOrderDTO buyOrderFail2 = createOrder(BUY, "100", "1.5");
        assertFalse(pendingOrderMatcherService.canMatchOrders(sellOrderFail2, buyOrderFail2), "SELL 주문이 BUY 주문의 가격 조건을 만족하지 않아야 함");

        // Case 5: 주문 수량이 0 => false 반환
        CoinOrderDTO zeroAmountOrder = createOrder(BUY, "100", "0");
        CoinOrderDTO validOppositeOrder = createOrder(SELL, "90", "1.5");
        assertFalse(pendingOrderMatcherService.canMatchOrders(zeroAmountOrder, validOppositeOrder), "수량이 0인 경우 false 반환");

        // Case 6: 수량 < 0 => false 반환
        CoinOrderDTO negativeAmountOrder = createOrder(BUY, "100", "-1.5");
        assertFalse(pendingOrderMatcherService.canMatchOrders(negativeAmountOrder, validOppositeOrder), "수량이 음수인 경우 false 반환");
    }

    @Test
    public void testGetOppositeOrdersQueue() {
        //given
        String key = "BTC-KRW";

        Comparator<CoinOrderDTO> buyOrderComparator =
                Comparator.comparing(CoinOrderDTO::getOrderPrice).reversed()
                        .thenComparing(CoinOrderDTO::getCreatedAt);

        Comparator<CoinOrderDTO> sellOrderComparator =
                Comparator.comparing(CoinOrderDTO::getOrderPrice)
                        .thenComparing(CoinOrderDTO::getCreatedAt);

        PriorityQueue<CoinOrderDTO> buyQueue = new PriorityQueue<>(buyOrderComparator);
        PriorityQueue<CoinOrderDTO> sellQueue = new PriorityQueue<>(sellOrderComparator);

        CoinOrderDTO buyOrder = createOrder(BUY, "100", "1.5");
        CoinOrderDTO sellOrder = createOrder(SELL, "90", "1.5");

        buyQueue.add(buyOrder);
        sellQueue.add(sellOrder);

        //when
        when(orderService.getBuyOrderQueue(key)).thenReturn(buyQueue);
        when(orderService.getSellOrderQueue(key)).thenReturn(sellQueue);

        //then
        PriorityQueue<CoinOrderDTO> resultForBuy = pendingOrderMatcherService.getOppositeOrdersQueue(buyOrder, key);
        assertEquals(sellQueue, resultForBuy, "BUY 주문은 SELL 큐를 반환");

        PriorityQueue<CoinOrderDTO> resultForSell  = pendingOrderMatcherService.getOppositeOrdersQueue(sellOrder, key);
        assertEquals(buyQueue, resultForSell , "SELL 주문은 BUY 큐를 반환");
    }

    @Test
    public void testUpdateOrderWithMatch() {
        //given
        CoinOrderDTO order = createOrder(BUY, "100", "1.5");
        CoinOrderDTO oppositeOrder = createOrder(SELL, "90", "1.5");

        BigDecimal executionPrice = new BigDecimal("100.0");

        // when
        pendingOrderMatcherService.updateOrderWithMatch(order, oppositeOrder, executionPrice);

        // then
        assertEquals(COMPLETED, order.getOrderStatus(), "주문 상태가 COMPLETED여야 한다.");
        assertNotNull(order.getMatchedAt(), "매칭 시간이 존재해야 한다.");
        assertEquals(executionPrice, order.getExecutionPrice(), "매치 가격이 같아야 한다.");
        assertEquals(order.getUuid() + "|" + oppositeOrder.getUuid(), order.getMatchIdx(), "매치 인덱스가 일치해야 한다.");
    }
}