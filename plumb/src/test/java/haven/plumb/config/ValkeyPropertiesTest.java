package haven.plumb.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The port is a String, and this is what pins it there.
 *
 * Kubernetes sets VALKEY_PORT="tcp://10.x.x.x:6379" for a Service named valkey
 * in the pod's namespace, and Spring ranks the environment above
 * application.yaml. Bound to an int that kills the process at startup — the one
 * thing plumb must never do, since it exists to explain a broken tenant rather
 * than join it. A later tidy-up back to `int port` compiles and passes every
 * other test in this repo, so these cases are the guard.
 */
class ValkeyPropertiesTest {

    @Test
    void a_real_port_parses() {
        assertThat(props("6379").portOrZero()).isEqualTo(6379);
        assertThat(props(" 6379 ").portOrZero()).isEqualTo(6379);
    }

    @Test
    void the_kubernetes_service_link_does_not_parse_and_does_not_throw() {
        assertThat(props("tcp://10.2.14.171:6379").portOrZero()).isZero();
    }

    @Test
    void absent_is_zero_rather_than_an_exception() {
        assertThat(props(null).portOrZero()).isZero();
        assertThat(props("").portOrZero()).isZero();
        assertThat(props("   ").portOrZero()).isZero();
    }

    @Test
    void configured_needs_a_host_and_a_real_port() {
        assertThat(props("6379").configured()).isTrue();
        assertThat(props("tcp://10.2.14.171:6379").configured()).isFalse();
        assertThat(new ValkeyProperties("", "6379", "plumb:").configured()).isFalse();
    }

    private static ValkeyProperties props(String port) {
        return new ValkeyProperties("valkey", port, "plumb:");
    }
}
