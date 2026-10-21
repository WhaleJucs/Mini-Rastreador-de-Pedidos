package com.foody.delivery.order;

import static com.foody.delivery.order.OrderStatusTransitions.Result.CHANGE;
import static com.foody.delivery.order.OrderStatusTransitions.Result.FORBIDDEN;
import static com.foody.delivery.order.OrderStatusTransitions.Result.NO_OP;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTransitionsTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @MethodSource("transitions")
    void classifiesEveryPair(OrderStatus current, OrderStatus requested, OrderStatusTransitions.Result expected) {
        assertThat(OrderStatusTransitions.classify(current, requested)).isEqualTo(expected);
    }

    @Test
    void preservesTheFiveContractStatusNames() {
        assertThat(OrderStatus.values()).extracting(Enum::name).containsExactly(
                "RECEBIDO", "EM_PREPARO", "SAIU_PARA_ENTREGA", "ENTREGUE", "CANCELADO");
    }

    private static Stream<Arguments> transitions() {
        var statuses = new OrderStatus[] {
                OrderStatus.RECEBIDO, OrderStatus.EM_PREPARO, OrderStatus.SAIU_PARA_ENTREGA,
                OrderStatus.ENTREGUE, OrderStatus.CANCELADO
        };
        var expected = new OrderStatusTransitions.Result[][] {
                { NO_OP, CHANGE, FORBIDDEN, FORBIDDEN, CHANGE },
                { FORBIDDEN, NO_OP, CHANGE, FORBIDDEN, CHANGE },
                { FORBIDDEN, FORBIDDEN, NO_OP, CHANGE, FORBIDDEN },
                { FORBIDDEN, FORBIDDEN, FORBIDDEN, NO_OP, FORBIDDEN },
                { FORBIDDEN, FORBIDDEN, FORBIDDEN, FORBIDDEN, NO_OP }
        };
        return IntStream.range(0, statuses.length).boxed().flatMap(row ->
                IntStream.range(0, statuses.length).mapToObj(column ->
                        Arguments.of(statuses[row], statuses[column], expected[row][column])));
    }
}
