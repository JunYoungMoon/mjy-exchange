package com.mjy.coin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mjy.coin.entity.coin.CoinOrder;
import com.mjy.coin.enums.OrderStatus;
import com.mjy.coin.enums.OrderType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class CoinOrderDTO {
    private Long memberId; // 주문 등록인, member_id
    private String marketName; // 예: KRW
    private String coinName; // 예: BTC
    private BigDecimal coinAmount; // 매수/매도 코인 개수
    private BigDecimal orderPrice; // 매수/매도 금액
    private OrderType orderType; // 매수/매도 타입(enum)
    private OrderStatus orderStatus; // 거래 상태 (체결/미체결/취소)
    private BigDecimal fee; //수수료
    private LocalDateTime createdAt; // 등록일자

    @JsonCreator // JSON 역직렬화를 위한 생성자
    public CoinOrderDTO(
            @JsonProperty("memberId") Long memberId,
            @JsonProperty("marketName") String marketName,
            @JsonProperty("coinName") String coinName,
            @JsonProperty("coinAmount") BigDecimal coinAmount,
            @JsonProperty("orderPrice") BigDecimal orderPrice,
            @JsonProperty("orderType") OrderType orderType,
            @JsonProperty("orderStatus") OrderStatus orderStatus,
            @JsonProperty("fee") BigDecimal fee,
            @JsonProperty("createdAt") LocalDateTime createdAt) {
        this.memberId = memberId;
        this.marketName = marketName;
        this.coinName = coinName;
        this.coinAmount = coinAmount;
        this.orderPrice = orderPrice;
        this.orderType = orderType;
        this.orderStatus = orderStatus;
        this.fee = fee;
        this.createdAt = createdAt;
    }

    // 엔티티에서 VO로 변환하는 정적 팩토리 메서드
    public static CoinOrderDTO fromEntity(CoinOrder entity) {
        return new CoinOrderDTO(
                entity.getMemberId(),
                entity.getMarketName(),
                entity.getCoinName(),
                entity.getCoinAmount(),
                entity.getOrderPrice(),
                entity.getOrderType(),
                entity.getOrderStatus(),
                entity.getFee(),
                entity.getCreatedAt()
        );
    }
}