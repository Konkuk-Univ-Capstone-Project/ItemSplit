package com.capstone.itemsplit.settlement;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SettlementTransferCalculatorTest {
    @Test void stableMemberOrderProducesPositiveTransfersAndSettlesEveryBalance() {
        var members=List.of(member(4,70),member(2,-60),member(5,0),member(1,-40),member(3,30));
        var transfers=SettlementTransferCalculator.calculate(members);
        assertThat(transfers).containsExactly(new SettlementTransferCalculator.Transfer(1L,3L,30),
            new SettlementTransferCalculator.Transfer(1L,4L,10),new SettlementTransferCalculator.Transfer(2L,4L,60));
        Map<Long,Long> balances=new HashMap<>();members.forEach(m->balances.put(m.memberId(),m.net()));
        transfers.forEach(t->{assertThat(t.amount()).isPositive();balances.merge(t.fromMemberId(),t.amount(),Long::sum);balances.merge(t.toMemberId(),-t.amount(),Long::sum);});
        assertThat(balances.values()).containsOnly(0L);
        var reversed=new ArrayList<>(members);Collections.reverse(reversed);
        assertThat(SettlementTransferCalculator.calculate(reversed)).isEqualTo(transfers);
    }
    @Test void zeroBalancesHaveNoTransfers() {assertThat(SettlementTransferCalculator.calculate(List.of(member(1,0)))).isEmpty();}
    private SettlementService.MemberSettlement member(long id,long net) {
        return new SettlementService.MemberSettlement(id,null,"member",false,Math.max(-net,0),Math.max(net,0),net);
    }
}
