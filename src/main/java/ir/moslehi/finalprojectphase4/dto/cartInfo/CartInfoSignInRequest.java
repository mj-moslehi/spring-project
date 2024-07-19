package ir.moslehi.finalprojectphase4.dto.cartInfo;

import ir.moslehi.finalprojectphase4.dto.orders.OrderIdRequest;
import jakarta.validation.constraints.Pattern;

public record CartInfoSignInRequest(

        @Pattern(regexp = "^[0-9]{16}$")
        String cartNumber,

        @Pattern(regexp = "^[0-9]{4,5}$")
        String cvv2,

        @Pattern(regexp = "^[0-9]{4,6}$")
        String password,

        @Pattern(regexp = "^((?:19|20)[0-9][0-9])-(0?[1-9]|1[012])$")
        String stringDate,

        OrderIdRequest order
) {
}
