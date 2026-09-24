package haven.plumb.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BasePathTest {

    @Test
    void without_an_edge_the_page_is_at_the_root() {
        assertThat(BasePath.of(new MockHttpServletRequest())).isEqualTo("/");
    }

    @Test
    void behind_the_edge_the_stripped_prefix_is_put_back() {
        // The regression: "Run all" redirected to a bare "/", which in a cell is
        // the tenant's root, not plumb — a 404.
        assertThat(BasePath.of(withPrefix("/plumb"))).isEqualTo("/plumb/");
        assertThat(BasePath.of(withPrefix("/plumb/"))).isEqualTo("/plumb/");
        assertThat(BasePath.of(withPrefix("/apps/plumb"))).isEqualTo("/apps/plumb/");
    }

    @Test
    void a_prefix_that_could_leave_the_app_is_ignored() {
        // It ends up in a Location header and in the markup.
        for (String hostile : new String[] {
            "//evil.example", "https://evil.example", "/plumb/../..", "/a\"><script>", "plumb", "", "/"
        }) {
            assertThat(BasePath.of(withPrefix(hostile))).as(hostile).isEqualTo("/");
        }
    }

    private static MockHttpServletRequest withPrefix(String prefix) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BasePath.HEADER, prefix);
        return request;
    }
}
