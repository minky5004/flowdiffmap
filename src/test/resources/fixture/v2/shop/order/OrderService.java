package shop.order;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order find(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("order " + id));
    }

    public void cancel(Long id) {
        orderRepository.deleteById(id);
    }
}
