package haven.plumb.web;

import haven.plumb.probe.ProbeRegistry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Serves the page and the one action on it. */
@Controller
public class PageController {

    private final Page page;
    private final ProbeRegistry registry;

    public PageController(Page page, ProbeRegistry registry) {
        this.page = page;
        this.registry = registry;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String index() {
        return page.render();
    }

    /**
     * Run everything, then redirect back to the page rather than rendering in
     * place: a rendered POST response leaves the browser one refresh away from
     * re-submitting, and re-running every probe by accident is not what someone
     * pressing F5 on a broken tenant wants.
     */
    @PostMapping("/run")
    public String runAll() {
        registry.runAll();
        return "redirect:/";
    }
}
