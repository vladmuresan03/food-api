package com.foodfinder.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Nothing is served at "/" itself — the public API lives under /api/** and
 * the admin UI under /admin/**. Browsers hitting the domain root get the
 * admin login page instead of a bare 404.
 */
@Controller
public class RootRedirectController {

    @GetMapping("/")
    public String root() {
        return "redirect:/admin/login";
    }
}
