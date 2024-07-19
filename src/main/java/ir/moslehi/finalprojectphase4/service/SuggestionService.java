package ir.moslehi.finalprojectphase4.service;

import ir.moslehi.finalprojectphase4.dto.suggestion.SuggestionSaveRequest;
import ir.moslehi.finalprojectphase4.exception.DuplicateInformationException;
import ir.moslehi.finalprojectphase4.exception.NotFoundException;
import ir.moslehi.finalprojectphase4.exception.NotValidInput;
import ir.moslehi.finalprojectphase4.mapper.SuggestionMapper;
import ir.moslehi.finalprojectphase4.model.*;
import ir.moslehi.finalprojectphase4.model.enums.OrderStatus;
import ir.moslehi.finalprojectphase4.repository.SuggestionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional
public class SuggestionService {

    private final SuggestionRepository suggestionRepository;
    private final ExpertService expertService;
    private final OrdersService ordersService;
    private final ExpertSubServiceService expertSubServiceService;
    private final CustomerService customerService;

    public Suggestion save(SuggestionSaveRequest request) {

        Suggestion mappedSuggestion = SuggestionMapper.INSTANCE.suggestionSaveRequestToModel(request);

        mappedSuggestion.setStartDate(ordersService.validDate(request.dateStringStart()));
        mappedSuggestion.setFinishDate(ordersService.validDate(request.dateStringFinish()));
        mappedSuggestion.setOrders(ordersForExpert(request.expert().id(), request.orders().id()));
        validPriceForSuggestion(request.orders().id(), mappedSuggestion.getProposedPrice());

        Orders orders = mappedSuggestion.getOrders();
        Expert expert = mappedSuggestion.getExpert();

        if (mappedSuggestion.getStartDate() == null || mappedSuggestion.getFinishDate() == null ||
                mappedSuggestion.getOrders() == null)
            throw new NullPointerException("some fields weren't valid");

        if (suggestionRepository.findByOrdersAndExpert(orders, expert).isPresent())
            throw new DuplicateInformationException(
                    suggestionRepository.findByOrdersAndExpert(orders, expert).get().getId() + " is duplicate");

        if (mappedSuggestion.getStartDate().after(mappedSuggestion.getFinishDate()) &&
                mappedSuggestion.getStartDate().after(orders.getDateOfOrder()))
            throw new NotValidInput("not valid date");

        return suggestionRepository.save(mappedSuggestion);
    }

    public Suggestion findByOrdersAndExpert(Orders orders, Expert expert) {
        if (suggestionRepository.findByOrdersAndExpert(orders, expert).isEmpty())
            throw new NotFoundException("there isn't any suggestion for these order and expert");
        return suggestionRepository.findByOrdersAndExpert(orders, expert).get();
    }

    public void validPriceForSuggestion(Long ordersId, Long price) {
        Orders orders = ordersService.findById(ordersId);
        if (orders.getSubService().getBasePrice() >= price)
            throw new NotValidInput("the price isn't valid");
    }

    public Orders ordersForExpert(Long expertId, Long orderId) {
        Expert expert = expertService.findById(expertId);
        Orders orders = null;
        List<Orders> orderList = expertSubServiceService.findSubServiceByExpert(expert).stream()
                .map(SubService::getOrders).flatMap(List::stream).filter(orders1 -> orders1.getOrderStatus().equals(
                        OrderStatus.WAITING_FOR_The_SUGGESTION_OF_EXPERTS)
                        ||
                        orders1.getOrderStatus().equals(OrderStatus.WAITING_FOR_SPECIALIST_SELECTION)).toList();

        if (orderList.contains(ordersService.findById(orderId))) orders = ordersService.findById(orderId);
        return orders;
    }

    public List<Orders> validOrdersForExpert(String email) {
        Expert expert = expertService.findByEmail(email);
        return expertSubServiceService.findSubServiceByExpert(expert).stream()
                .map(SubService::getOrders).flatMap(List::stream).filter(orders1 -> orders1.getOrderStatus().equals(
                        OrderStatus.WAITING_FOR_The_SUGGESTION_OF_EXPERTS)
                        ||
                        orders1.getOrderStatus().equals(OrderStatus.WAITING_FOR_SPECIALIST_SELECTION)).toList();
    }

    public Orders validExpertForOrder(Long expertId, Long orderId, String customerEmail) {
        Customer customer = customerService.findByEmail(customerEmail);
        Orders orders = validOrderForCustomerWithOrderStatus(
                OrderStatus.WAITING_FOR_SPECIALIST_SELECTION, customer, orderId);
        List<Expert> expertList = suggestionListSorted(orders.getId()).stream().map(Suggestion::getExpert).toList();
        if (expertList.contains(expertService.findById(expertId))) {
            orders.setExpert(expertService.findById(expertId));
            return ordersService.updateOrderStatus(orders, OrderStatus.WAITING_FOR_THE_SPECIALIST_TO_COME_TO_YOUR_PLACE);
        } else throw new NotValidInput("expert wasn't valid for your order");
    }

    public List<Suggestion> suggestionListSorted(Long orderId) {
        return suggestionRepository.suggestionListSorted(orderId);
    }

    public Orders validOrderForCustomerWithOrderStatus(OrderStatus orderStatus, Customer customer, Long orderId) {
        List<Orders> ordersList = ordersService.findByCustomerAndOrderStatus(customer, orderStatus);
        if (ordersList.contains(ordersService.findById(orderId))) return ordersService.findById(orderId);
        else throw new NotValidInput("order wasn't valid");
    }

    public Orders updateOrderStatusToStarted(String customerEmail, Long orderId) {
        Date now = new Date();
        Customer customer = customerService.findByEmail(customerEmail);
        Orders orders = validOrderForCustomerWithOrderStatus
                (OrderStatus.WAITING_FOR_THE_SPECIALIST_TO_COME_TO_YOUR_PLACE, customer, orderId);
        if (findByOrdersAndExpert(orders, orders.getExpert()).getStartDate().before(now))
            return ordersService.updateOrderStatus(orders, OrderStatus.STARTED);
        else throw new NotFoundException("there isn't any suggestion for updating");
    }

    public Orders updateOrderStatusToDone(String customerEmail, Long orderId) {
        Date now = new Date();
        Customer customer = customerService.findByEmail(customerEmail);
        Orders orders = validOrderForCustomerWithOrderStatus(OrderStatus.STARTED, customer, orderId);
        if (findByOrdersAndExpert(orders, orders.getExpert()).getFinishDate().before(now)) {
            Expert expert = orders.getExpert();
            long difference =
                    now.getTime() - findByOrdersAndExpert(orders, orders.getExpert()).getFinishDate().getTime();
            double hour = (double) TimeUnit.MILLISECONDS.toHours(difference);
            expertService.updateScoreWithDelayHour(expert, hour);
            return ordersService.updateOrderStatus(orders, OrderStatus.DONE);
        } else throw new NotValidInput("the suggestion wasn't finished");
    }

    public Orders validity(String customerEmail, Long orderId) {
        Customer customer = customerService.findByEmail(customerEmail);
        Orders orders = validOrderForCustomerWithOrderStatus(OrderStatus.DONE, customer, orderId);
        Expert expert = orders.getExpert();
        Long price = findByOrdersAndExpert(orders, expert).getProposedPrice();
        customerService.updateValidity(customer, price);
        expertService.updateValidity(expert, price);
        return ordersService.updateOrderStatus(orders, OrderStatus.PAID);
    }


}