package com.hmdp.controller;

import com.hmdp.annotation.OperationLog;
import com.hmdp.dto.Result;
import com.hmdp.entity.Venue;
import com.hmdp.entity.VenueCourt;
import com.hmdp.entity.VenueSlot;
import com.hmdp.service.IBookingOrderService;
import com.hmdp.service.IVenueService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin")
public class AdminController {
    @Resource
    private IVenueService venueService;

    @Resource
    private IBookingOrderService bookingOrderService;

    @GetMapping("/venues")
    public Result venues() {
        return venueService.listVenues(null, null, true);
    }

    @PostMapping("/venues")
    @OperationLog(value = "SAVE_VENUE", targetType = "VENUE")
    public Result saveVenue(@RequestBody Venue venue) {
        return venueService.saveVenue(venue);
    }

    @GetMapping("/courts")
    public Result courts(@RequestParam Long venueId) {
        return venueService.listCourts(venueId, true);
    }

    @PostMapping("/courts")
    @OperationLog(value = "SAVE_COURT", targetType = "COURT")
    public Result saveCourt(@RequestBody VenueCourt court) {
        return venueService.saveCourt(court);
    }

    @GetMapping("/slots")
    public Result slots(
            @RequestParam Long courtId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return venueService.listSlots(courtId, date, true);
    }

    @PostMapping("/slots")
    @OperationLog(value = "PUBLISH_SLOT", targetType = "SLOT")
    public Result publish(@RequestBody VenueSlot slot) {
        return venueService.publishSlot(slot);
    }

    @PutMapping("/slots/{id}/enabled")
    @OperationLog(value = "SET_SLOT_ENABLED", targetType = "SLOT", idArgument = 0)
    public Result enabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return venueService.setSlotEnabled(id, enabled);
    }

    @GetMapping("/orders")
    public Result orders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer current) {
        return bookingOrderService.listOrders(status, current, true);
    }

    @PutMapping("/orders/{id}/verify")
    @OperationLog(value = "VERIFY_BOOKING", targetType = "ORDER", idArgument = 0)
    public Result verify(@PathVariable String id, @RequestParam String code) {
        return bookingOrderService.verifyOrder(id, code);
    }
}
