package shop.order;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Order {

    @Id
    private Long id;
    private long amount;
    private String status;

    public long getAmount() {
        return amount;
    }
}
