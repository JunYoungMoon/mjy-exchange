package com.mjy.coin.service;

import com.mjy.coin.dto.CoinOrderDTO;
import com.mjy.coin.dto.PriceVolumeDTO;
import com.mjy.coin.enums.OrderStatus;
import com.mjy.coin.enums.OrderType;
import com.mjy.coin.repository.coin.master.MasterCoinOrderRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

import static com.mjy.coin.enums.OrderStatus.COMPLETED;
import static com.mjy.coin.enums.OrderStatus.PENDING;
import static com.mjy.coin.enums.OrderType.BUY;
import static com.mjy.coin.enums.OrderType.SELL;

@Component
public class PendingOrderMatcherService {
    private final MasterCoinOrderRepository masterCoinOrderRepository;
    private final OrderBookService orderBookService;
    private final OrderService orderService;
    private final RedisService redisService;
    private final KafkaTemplate<String, Map<String, List<CoinOrderDTO>>> matchListKafkaTemplate;
    private final KafkaTemplate<String, Map<String, List<PriceVolumeDTO>>> priceVolumeMapKafkaTemplate;

    public PendingOrderMatcherService(MasterCoinOrderRepository masterCoinOrderRepository,
                                      OrderService orderService,
                                      OrderBookService orderBookService,
                                      RedisService redisService,
                                      @Qualifier("matchListKafkaTemplate") KafkaTemplate<String, Map<String, List<CoinOrderDTO>>> matchListKafkaTemplate,
                                      @Qualifier("priceVolumeMapKafkaTemplate") KafkaTemplate<String, Map<String, List<PriceVolumeDTO>>> priceVolumeMapKafkaTemplate) {
        this.masterCoinOrderRepository = masterCoinOrderRepository;
        this.orderBookService = orderBookService;
        this.orderService = orderService;
        this.redisService = redisService;
        this.matchListKafkaTemplate = matchListKafkaTemplate;
        this.priceVolumeMapKafkaTemplate = priceVolumeMapKafkaTemplate;
    }

    // 체결 로직
    public void matchOrders(String key) {
        BigDecimal executionPrice;

        PriorityQueue<CoinOrderDTO> buyOrders = orderService.getBuyOrderQueue(key);
        PriorityQueue<CoinOrderDTO> sellOrders = orderService.getSellOrderQueue(key);

        if (buyOrders != null && sellOrders != null) {
            List<CoinOrderDTO> matchList = new ArrayList<>();
            List<PriceVolumeDTO> priceVolumeList = new ArrayList<>();

            while (!buyOrders.isEmpty() && !sellOrders.isEmpty()) {
                CoinOrderDTO buyOrder = buyOrders.peek();
                CoinOrderDTO sellOrder = sellOrders.peek();

                // 체결 가능 조건 확인
                // 체결 조건에서 최종적으로 결정되는 기준은 매수자의 가격
                if (buyOrder.getOrderPrice().compareTo(sellOrder.getOrderPrice()) >= 0) {
                    // 체결 가능 시 처리
                    BigDecimal buyQuantity = buyOrder.getCoinAmount();
                    BigDecimal sellQuantity = sellOrder.getCoinAmount();
                    //buyQuantity - sellQuantity 계산값을 소수점 8자리까지 표현한 결과가 저장됩니다.
                    BigDecimal remainingQuantity = buyQuantity.subtract(sellQuantity).setScale(8, RoundingMode.DOWN).stripTrailingZeros();

                    if (remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
                        // 완전체결
                        // 매수와 매도 모두 체결
                        System.out.println("Matched completely: BuyOrder: " + buyOrder + " with SellOrder: " + sellOrder);

                        executionPrice = buyOrder.getOrderPrice(); //실제 체결 되는 가격은 매수자의 가격으로 체결

                        // 주문 삽입 (완전체결인 경우)
                        // 매수와 매도 모두 체결로 처리
                        buyOrder.setOrderStatus(COMPLETED);
                        buyOrder.setMatchedAt(LocalDateTime.now());
                        buyOrder.setMatchIdx(buyOrder.getIdx() + "|" + sellOrder.getIdx());
                        buyOrder.setExecutionPrice(executionPrice);
                        sellOrder.setOrderStatus(COMPLETED);
                        sellOrder.setMatchedAt(LocalDateTime.now());
                        sellOrder.setMatchIdx(buyOrder.getIdx() + "|" + sellOrder.getIdx());
                        sellOrder.setExecutionPrice(executionPrice);

                        // 매수와 매도 체결된 상태를 DB에 기록
//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(buyOrder));
//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(sellOrder));

                        //////////////////////////////////시작////////////////////////////////////
                        // 1. BuyOrder 업데이트
                        buyOrder.setMatchIdx(buyOrder.getUuid() + "|" + sellOrder.getUuid());
                        redisService.deleteHashOps(PENDING + ":ORDER:" + key, buyOrder.getUuid());
                        redisService.insertOrderInRedis(key, COMPLETED, buyOrder);

                        // 2. SellOrder 업데이트
                        sellOrder.setMatchIdx(buyOrder.getUuid() + "|" + sellOrder.getUuid());
                        redisService.deleteHashOps(PENDING + ":ORDER:" + key, sellOrder.getUuid());
                        redisService.insertOrderInRedis(key, COMPLETED, sellOrder);
                        //////////////////////////////////끝////////////////////////////////////

                        // 큐에서 양쪽 주문 제거
                        buyOrders.poll();
                        sellOrders.poll();

                        // 체결 되었으니 호가 리스트 제거
                        orderBookService.updateOrderBook(key, buyOrder, true, false);
                        orderBookService.updateOrderBook(key, sellOrder, false, false);

                        //체결 완료 된 데이터를 쌓아서 kafka로 전달할 list
                        priceVolumeList.add(new PriceVolumeDTO(buyOrder));
                        matchList.add(new CoinOrderDTO(buyOrder));
                        matchList.add(new CoinOrderDTO(sellOrder));
                    } else if (remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
                        // 매수량이 매도량을 초과
                        // 매수는 일부 남고 매도는 모두 체결
                        System.out.println("Partial match (remaining buy): BuyOrder: " + buyOrder + " with SellOrder: " + sellOrder);

                        // 주문 생성 시간 비교하여 오래된 주문의 가격을 체결가로 설정
                        if (buyOrder.getCreatedAt().isAfter(sellOrder.getCreatedAt())) {
                            executionPrice = sellOrder.getOrderPrice(); // 매도 주문이 먼저 생성된 경우
                        } else {
                            executionPrice = buyOrder.getOrderPrice(); // 매수 주문이 먼저 생성된 경우
                        }

                        // 매도 모두 체결 처리
                        sellOrder.setOrderStatus(COMPLETED);
                        sellOrder.setMatchedAt(LocalDateTime.now());
                        sellOrder.setMatchIdx(buyOrder.getIdx() + "|" + sellOrder.getIdx());
                        sellOrder.setExecutionPrice(executionPrice);   //실제 체결 되는 가격은 매수자의 가격으로 체결

//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(sellOrder));

                        //////////////////////////////////시작////////////////////////////////////
                        // 1. SellOrder 업데이트
                        sellOrder.setMatchIdx(buyOrder.getUuid() + "|" + sellOrder.getUuid());

                        redisService.deleteHashOps(PENDING + ":ORDER:" + key, sellOrder.getUuid());
                        redisService.insertOrderInRedis(key, COMPLETED, sellOrder);
                        //////////////////////////////////끝////////////////////////////////////

                        // 매도 주문 제거
                        sellOrders.poll();

                        // 이미 미체결을 넣어줬기 때문에 체결 되었으니 호가 리스트 제거(가격만 구분하고 수량 차감은 같이 한다.)
                        orderBookService.updateOrderBook(key, sellOrder, false, false);

                        // 매수 이전 idx 저장
                        Long previousIdx = buyOrder.getIdx();

                        // 매도가 체결 되는 만큼 매수도 체결
                        // idx가 빈값으로 들어가 insert 필요
                        buyOrder.setIdx(null);
                        buyOrder.setOrderStatus(COMPLETED);
                        buyOrder.setCoinAmount(sellOrder.getCoinAmount());
                        buyOrder.setMatchedAt(LocalDateTime.now());
                        buyOrder.setMatchIdx(previousIdx + "-" + sellOrder.getIdx());
                        buyOrder.setExecutionPrice(executionPrice);   //실제 체결 되는 가격은 매수자의 가격으로 체결

//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(buyOrder));

                        //////////////////////////////////시작////////////////////////////////////
                        String previousUUID = buyOrder.getUuid();

                        // 2. 새로운 BuyOrder 생성
                        String uuid = buyOrder.getMemberIdx() + "_" + UUID.randomUUID();
                        buyOrder.setUuid(uuid);
                        buyOrder.setMatchIdx(previousUUID + "|" + sellOrder.getUuid());
                        redisService.deleteHashOps(PENDING + ":ORDER:" + key, buyOrder.getUuid());
                        redisService.insertOrderInRedis(key, COMPLETED, buyOrder);

                        //체결 완료 된 데이터를 쌓아서 kafka로 전달할 list
                        priceVolumeList.add(new PriceVolumeDTO(sellOrder));
                        matchList.add(new CoinOrderDTO(buyOrder));
                        matchList.add(new CoinOrderDTO(sellOrder));
                        //////////////////////////////////끝////////////////////////////////////

                        // 이미 미체결을 넣어줬기 때문에 체결 되었으니 호가 리스트 제거(가격만 구분하고 수량 차감은 같이 한다.)
                        orderBookService.updateOrderBook(key, buyOrder, true, false);

                        // 매수 주문 수량 업데이트 (남은 수량)
                        // 기존의 idx를 가져와 기존 매수 update
                        buyOrder.setIdx(previousIdx);
                        buyOrder.setCoinAmount(remainingQuantity);
                        buyOrder.setOrderStatus(PENDING);

                        // 미체결 수량 업데이트
//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(buyOrder)); // 상태 업데이트

                        //////////////////////////////////시작////////////////////////////////////
                        // 3. BuyOrder 업데이트
                        buyOrder.setUuid(previousUUID);

                        redisService.updateOrderInRedis(buyOrder);
                        //////////////////////////////////끝////////////////////////////////////

                        // 우선순위 큐에서 매수 주문 수량도 업데이트
                        buyOrders.poll(); // 기존 주문 제거
                        buyOrders.offer(buyOrder); // 수정된 주문 다시 추가
                    } else {
                        // 매도량이 매수량을 초과
                        // 매도는 일부 남고 매수는 모두 체결
                        System.out.println("Partial match (remaining sell): BuyOrder: " + buyOrder + " with SellOrder: " + sellOrder);

                        // 주문 생성 시간 비교하여 오래된 주문의 가격을 체결가로 설정
                        if (buyOrder.getCreatedAt().isAfter(sellOrder.getCreatedAt())) {
                            executionPrice = sellOrder.getOrderPrice(); // 매도 주문이 먼저 생성된 경우
                        } else {
                            executionPrice = buyOrder.getOrderPrice(); // 매수 주문이 먼저 생성된 경우
                        }

                        // 매수 모두 체결 처리
                        buyOrder.setOrderStatus(COMPLETED);
                        buyOrder.setMatchedAt(LocalDateTime.now());
                        buyOrder.setMatchIdx(buyOrder.getIdx() + "|" + sellOrder.getIdx());
                        buyOrder.setExecutionPrice(executionPrice);   //실제 체결 되는 가격은 매수자의 가격으로 체결

//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(buyOrder));

                        //////////////////////////////////시작////////////////////////////////////
                        // 1. SellOrder 업데이트
                        buyOrder.setMatchIdx(buyOrder.getUuid() + "|" + sellOrder.getUuid());

                        redisService.deleteHashOps(PENDING + ":ORDER:" + key, buyOrder.getUuid());
                        redisService.insertOrderInRedis(key, COMPLETED, buyOrder);
                        //////////////////////////////////끝////////////////////////////////////

                        // 매수 주문 제거
                        buyOrders.poll();

                        // 이미 미체결을 넣어줬기 때문에 체결 되었으니 호가 리스트 제거(가격만 구분하고 수량 차감은 같이 한다.)
                        orderBookService.updateOrderBook(key, buyOrder, true, false);

                        //이전 idx 저장
                        Long previousIdx = sellOrder.getIdx();

                        // 매수가 체결 되는 만큼 매도도 체결
                        // idx가 빈값으로 들어가 insert 필요
                        sellOrder.setIdx(null);
                        sellOrder.setOrderStatus(COMPLETED);
                        sellOrder.setCoinAmount(buyOrder.getCoinAmount());
                        sellOrder.setMatchedAt(LocalDateTime.now());
                        sellOrder.setMatchIdx(buyOrder.getIdx() + "|" + previousIdx);
                        sellOrder.setExecutionPrice(executionPrice);   //실제 체결 되는 가격은 매수자의 가격으로 체결

//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(sellOrder));

                        //////////////////////////////////시작////////////////////////////////////
                        String previousUUID = sellOrder.getUuid();

                        // 2. 새로운 BuyOrder 생성
                        String uuid = sellOrder.getMemberIdx() + "_" + UUID.randomUUID();
                        sellOrder.setUuid(uuid);
                        sellOrder.setMatchIdx(previousUUID + "|" + sellOrder.getUuid());
                        redisService.deleteHashOps(PENDING + ":ORDER:" + key, sellOrder.getUuid());
                        redisService.insertOrderInRedis(key, COMPLETED, sellOrder);

                        //체결 완료 된 데이터를 쌓아서 kafka로 전달할 list
                        priceVolumeList.add(new PriceVolumeDTO(buyOrder));
                        matchList.add(new CoinOrderDTO(buyOrder));
                        matchList.add(new CoinOrderDTO(sellOrder));
                        //////////////////////////////////끝////////////////////////////////////

                        // 이미 미체결을 넣어줬기 때문에 체결 되었으니 호가 리스트 제거(가격만 구분하고 수량 차감은 같이 한다.)
                        orderBookService.updateOrderBook(key, sellOrder, false, false);

                        // 매수 주문 수량 업데이트 (남은 수량)
                        // 기존의 idx를 가져와 update 필요
                        sellOrder.setIdx(previousIdx);
                        sellOrder.setCoinAmount(remainingQuantity.negate());
                        sellOrder.setOrderStatus(PENDING);

                        // 미체결 수량 업데이트
//                        masterCoinOrderRepository.save(CoinOrderMapper.toEntity(sellOrder)); // 상태 업데이트

                        //////////////////////////////////시작////////////////////////////////////
                        // 3. BuyOrder 업데이트
                        sellOrder.setUuid(previousUUID);

                        redisService.updateOrderInRedis(sellOrder);
                        //////////////////////////////////끝////////////////////////////////////

                        // 우선순위 큐에서 매도 주문 수량도 업데이트
                        sellOrders.poll(); // 기존 주문 제거
                        sellOrders.offer(sellOrder); // 수정된 주문 다시 추가
                    }
                } else {
                    break; // 더 이상 체결할 수 없으면 중단
                }
            }

            //반복하는 동안 쌓인 가격과 볼륨 리스트 kafka로 전달(실시간 차트에서 사용)
            if (!priceVolumeList.isEmpty()) {
                Map<String, List<PriceVolumeDTO>> priceVolumeMap = new HashMap<>();
                priceVolumeMap.put(key, priceVolumeList);
                priceVolumeMapKafkaTemplate.send("Price-Volume", priceVolumeMap);
            }

            //반복하는 동안 쌓인 완료 주문 리스트 kafka로 전달(웹소켓을 통해 완료 리스트를 사용자에게 전달하기 위함)
            if (!matchList.isEmpty()) {
                Map<String, List<CoinOrderDTO>> matchListeMap = new HashMap<>();
                matchListeMap.put(key, matchList);
                matchListKafkaTemplate.send("Match-List", matchListeMap);
            }
        }
    }

    // 체결 로직2
    public void matchOrders2(CoinOrderDTO order) {
        BigDecimal executionPrice;

        String key = order.getCoinName() + "-" + order.getMarketName();

        // 반대 주문 가져오기 : 매수 주문이면 매도 큐를, 매도 주문이면 매수 큐를 가져온다.
        PriorityQueue<CoinOrderDTO> oppositeOrdersQueue =
                (order.getOrderType() == BUY)
                        ? orderService.getSellOrderQueue(key)
                        : orderService.getBuyOrderQueue(key);

        // 체결 처리 로직 시작
        while (!oppositeOrdersQueue.isEmpty()) {
            // 반대 주문의 최우선 데이터 가져오기
            CoinOrderDTO oppositeOrder = oppositeOrdersQueue.peek();

            // 현재 주문과 반대 주문의 가격 및 수량 정보
            BigDecimal currentOrderPrice = order.getOrderPrice();
            BigDecimal oppositeOrderPrice = oppositeOrder.getOrderPrice();
            BigDecimal remainingQuantity = order.getCoinAmount().subtract(oppositeOrder.getCoinAmount());

            // 매수 가격이 매도 가격보다 크거나 같으면 true, 매도 가격이 매수 가격보다 작거나 같으면 true
            boolean isPriceMatching =
                    (order.getOrderType() == BUY && currentOrderPrice.compareTo(oppositeOrderPrice) >= 0) ||
                    (order.getOrderType() == SELL && currentOrderPrice.compareTo(oppositeOrderPrice) <= 0);

            // 주문 수량이 0보다 작거나 같고 반대 주문과 가격이 맞지 않을때 벗어난다.
            boolean isOrderInvalid = order.getCoinAmount().compareTo(BigDecimal.ZERO) <= 0 || !isPriceMatching;

            if (isOrderInvalid) {
                break; // 매칭되지 않으면 더 이상 체결할 수 없으므로 종료
            }

            if (remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
                // 완전 체결
                // 매수와 매도 주문이 동일한 수량으로 체결된 경우
                System.out.println("완전체결 : " + " 주문 : " + order + " 반대 주문 : " + oppositeOrder);

                //실제 체결 되는 가격은 반대 주문 가격 설정
                executionPrice = oppositeOrder.getOrderPrice();

                // 주문과 반대주문 모두 체결로 처리
                // 주문건은 redis에 바로 넣으면 되고 반대 주문은 redis에서 미체결 제거후 체결 데이터로 전환
                order.setOrderStatus(COMPLETED);
                order.setMatchedAt(LocalDateTime.now());
                order.setExecutionPrice(executionPrice);
                order.setMatchIdx(order.getUuid() + "|" + oppositeOrder.getUuid());
                redisService.insertOrderInRedis(key, COMPLETED, order);

                oppositeOrder.setOrderStatus(COMPLETED);
                oppositeOrder.setMatchedAt(LocalDateTime.now());
                oppositeOrder.setExecutionPrice(executionPrice);
                oppositeOrder.setMatchIdx(oppositeOrder.getUuid() + "|" + order.getUuid());
                redisService.deleteHashOps(PENDING + ":ORDER:" + key, oppositeOrder.getUuid());
                redisService.insertOrderInRedis(key, COMPLETED, oppositeOrder);

                // 나는 수량을 0으로
                order.setCoinAmount(BigDecimal.valueOf(0));
                // 상대는 우선순위큐 poll
                oppositeOrdersQueue.poll();

                // 체결 되었으니 반대 주문 호가 리스트 제거
                orderBookService.updateOrderBook(key, oppositeOrder, oppositeOrder.getOrderType() == BUY, false);
            } else if (remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
                // 부분체결
                // 나의 주문 수량이 반대 주문수량 보다 클경우
                System.out.println("부분체결 (주문이 반대 주문보다 크다) : " + " 주문 : " + order + " 반대 주문 : " + oppositeOrder);

                // 반대 주문 체결가로 지정
                executionPrice = oppositeOrder.getOrderPrice();

                // 반대 주문 모두 체결 처리
                oppositeOrder.setOrderStatus(COMPLETED);
                oppositeOrder.setMatchedAt(LocalDateTime.now());
                oppositeOrder.setMatchIdx(oppositeOrder.getUuid() + "|" + order.getUuid());
                oppositeOrder.setExecutionPrice(executionPrice);

                redisService.deleteHashOps(PENDING + ":ORDER:" + key, oppositeOrder.getUuid());
                redisService.insertOrderInRedis(key, COMPLETED, oppositeOrder);

                // 나의 주문 부분 체결 처리
                String previousUUID = order.getUuid();
                String uuid = order.getMemberIdx() + "_" + UUID.randomUUID();

                order.setUuid(uuid);
                order.setOrderStatus(COMPLETED);
                order.setMatchedAt(LocalDateTime.now());
                order.setExecutionPrice(executionPrice);
                order.setMatchIdx(order.getUuid() + "|" + oppositeOrder.getUuid());
                order.setCoinAmount(oppositeOrder.getCoinAmount());

                redisService.insertOrderInRedis(key, COMPLETED, order);

                // 남은 수량을 잔여 수량으로 설정
                order.setOrderStatus(PENDING);
                order.setUuid(previousUUID);
                order.setCoinAmount(remainingQuantity);
                order.setExecutionPrice(null);
                // 상대는 우선순위큐 poll
                oppositeOrdersQueue.poll();
            } else {
                // 부분 체결
                // 나의 주문수량이 반대 주문수량 보다 작을 경우
                System.out.println("부분체결 (주문이 반대 주문보다 작다) : " + " 주문 : " + order + " 반대 주문 : " + oppositeOrder);

                // 반대 주문을 체결가로 지정
                executionPrice = oppositeOrder.getOrderPrice();
                
                // 나의 주문 모두 체결 처리
                order.setOrderStatus(COMPLETED);
                order.setMatchedAt(LocalDateTime.now());
                order.setMatchIdx(order.getUuid() + "|" + oppositeOrder.getUuid());
                order.setExecutionPrice(executionPrice);

                redisService.insertOrderInRedis(key, COMPLETED, order);

                // 반대 주문 부분 체결 처리
                String previousUUID = oppositeOrder.getUuid();
                String uuid = oppositeOrder.getMemberIdx() + "_" + UUID.randomUUID();

                oppositeOrder.setUuid(uuid);
                oppositeOrder.setOrderStatus(COMPLETED);
                oppositeOrder.setMatchedAt(LocalDateTime.now());
                oppositeOrder.setExecutionPrice(executionPrice);
                oppositeOrder.setMatchIdx(oppositeOrder.getUuid() + "|" + order.getUuid());
                oppositeOrder.setCoinAmount(order.getCoinAmount());

                redisService.insertOrderInRedis(key, COMPLETED, oppositeOrder);

                // 남은 수량을 잔여 수량으로 설정
                oppositeOrder.setOrderStatus(PENDING);
                oppositeOrder.setUuid(previousUUID);
                oppositeOrder.setCoinAmount(remainingQuantity);
                oppositeOrder.setExecutionPrice(null);
                oppositeOrder.setMatchIdx("");

                redisService.deleteHashOps(PENDING + ":ORDER:" + key, previousUUID);
                redisService.insertOrderInRedis(key, PENDING, oppositeOrder);
            }
        }

        // 남은 주문 정보 그대로 미체결 입력
        if(order.getOrderPrice().compareTo(BigDecimal.ZERO) > 0) {
            redisService.insertOrderInRedis(key, PENDING, order);

            if (order.getOrderType() == OrderType.BUY) {
                System.out.println("Adding buy order to queue: " + order);
                orderService.addBuyOrder(key, order);
                orderBookService.updateOrderBook(key, order, true, true);
            } else if (order.getOrderType() == OrderType.SELL) {
                System.out.println("Adding sell order to queue: " + order);
                orderService.addSellOrder(key, order);
                orderBookService.updateOrderBook(key, order, false, true);
            }
        }
    }
}
