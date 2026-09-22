package shop.order;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public OrderService(OrderRepository orderRepository, PaymentRepository paymentRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    public Order find(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    public Order create(Order order) {
        if (order.getAmount() <= 0) {
            throw new IllegalArgumentException("amount");
        }
        paymentRepository.charge(order);
        return orderRepository.save(order);
    }
}
