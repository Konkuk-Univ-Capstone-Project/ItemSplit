package com.capstone.itemsplit.item;

import com.capstone.itemsplit.receipt.Receipt;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private int quantity;

    private Item(Receipt receipt, String name, int price, int quantity) {
        this.receipt = receipt;
        this.name = name;
        this.price = price;
        this.quantity = quantity;
    }

    public static Item create(Receipt receipt, String name, int price, int quantity) {
        return new Item(receipt, name, price, quantity);
    }

    public void update(String name, int price, int quantity) {
        this.name = name;
        this.price = price;
        this.quantity = quantity;
    }
}
