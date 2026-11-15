package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVenueService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import javax.annotation.Resource;

@RestController
@RequestMapping("/venue")
public class VenueController {
    @Resource
    private IVenueService venueService;

    @GetMapping("/list")
    public Result list(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String category) {
        return venueService.listVenues(name, category, false);
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable Long id) {
        return venueService.venueDetail(id);
    }

    @GetMapping("/{id}/courts")
    public Result courts(@PathVariable Long id) {
        return venueService.listCourts(id, false);
    }

    @GetMapping("/courts/{id}/slots")
    public Result slots(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return venueService.listSlots(id, date, false);
    }
}
