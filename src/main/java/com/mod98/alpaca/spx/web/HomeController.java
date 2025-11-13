package com.mod98.alpaca.spx.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "SPX App is running successfully on port 8080 .. ✅✅✅ ";
    }
}

