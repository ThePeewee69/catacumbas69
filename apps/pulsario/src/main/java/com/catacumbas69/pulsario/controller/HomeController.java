package com.catacumbas69.pulsario.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page UI at {@code /}.
 */
@Controller
public class HomeController {

    /**
     * Forwards the root path to the static page.
     *
     * @return forward to {@code /index.html}
     */
    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
