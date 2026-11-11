package com.capstone.itemsplit.settlement;
import java.util.*;
/** Stable member-ID order; does not attempt global minimization of transfer count. */
final class SettlementTransferCalculator {
    private SettlementTransferCalculator() { }
    static List<Transfer> calculate(List<SettlementService.MemberSettlement> members) {
        var ordered=members.stream().sorted(Comparator.comparing(SettlementService.MemberSettlement::memberId)).toList();
        long[] balances=ordered.stream().mapToLong(SettlementService.MemberSettlement::net).toArray();
        if(Arrays.stream(balances).sum()!=0) throw new IllegalStateException("Settlement must balance");
        List<Transfer> result=new ArrayList<>();
        int debtor=0,creditor=0;
        while(true) {
            while(debtor<balances.length && balances[debtor]>=0) debtor++;
            while(creditor<balances.length && balances[creditor]<=0) creditor++;
            if(debtor==balances.length || creditor==balances.length) break;
            long amount=Math.min(-balances[debtor],balances[creditor]);
            result.add(new Transfer(ordered.get(debtor).memberId(),ordered.get(creditor).memberId(),amount));
            balances[debtor]+=amount;balances[creditor]-=amount;
        }
        return List.copyOf(result);
    }
    record Transfer(Long fromMemberId,Long toMemberId,long amount) { }
}
