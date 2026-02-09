package com.ws16289.daxi.ai.tool;

import com.ws16289.daxi.po.Campus;
import com.ws16289.daxi.po.Reservation;
import com.ws16289.daxi.service.CampusService;
import com.ws16289.daxi.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class ReservationTools {

     private final ReservationService reservationService;

    @Tool(name = "makeReservation", description = "当客户预约登记时调用,返回预约单号。保存预约人姓名，预约人联系方式和备注")
    public Integer reservation(@ToolParam(description = "预约人姓名")String name,
                                    @ToolParam(description = "预约人联系方式")String contact,
                                    @ToolParam(description = "备注",required = false)String comment){
        log.info("========== 当客户需要预约登记时调用==========");
        Reservation reservation = new Reservation();
        reservation.setName(name);
        reservation.setContact(contact);
        reservation.setComment(comment);
        return reservationService.insert(reservation);
    }

}
