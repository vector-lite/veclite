package veclite.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "veclite.web.enabled", havingValue = "true")
public class VectorLiteUiController {

    @GetMapping({"/", "/ui"})
    public String index() {
        return "forward:/index.html";
    }
}
